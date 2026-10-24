package com.tk.quicksearch.search.deviceSettings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.tk.quicksearch.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File

private const val TAG = "SystemSettingsIndexer"
private const val INDEX_FORMAT_VERSION = 1
private const val CACHE_FILE_NAME = "system_settings_index.json"
private const val ID_PREFIX = "sys:"
private const val BREADCRUMB_SEPARATOR = " > "
private const val FRAGMENT_CLASS_META_DATA = "com.android.settings.FRAGMENT_CLASS"
private const val TOP_LEVEL_SCREEN_PREFIX = "top_level_settings"
private const val MAX_RESOURCE_TYPE_ID = 0x40
private const val TYPE_PROBE_ENTRY_COUNT = 8
private const val MAX_CONSECUTIVE_MISSING_ENTRIES = 64
private const val MAX_TITLE_LENGTH = 40
private const val MAX_SCREENS = 2_000

/** Top-level screens shipped by AOSP. Vendor builds usually add their own variant. */
private val AOSP_TOP_LEVEL_SCREENS =
    setOf(
        "top_level_settings",
        "top_level_settings_v2",
        "top_level_settings_expressive",
        "top_level_settings_grouped",
    )

/** Preference widgets whose title is explanatory text rather than a setting. */
private val NON_SETTING_TAG_MARKERS = listOf("Category", "Footer", "TopIntro", "Banner", "Illustration", "Space")
private val KEYWORD_SEPARATOR_REGEX = "[,，;；、]".toRegex()

/**
 * Builds a deep index of the system settings so that options nested several pages down
 * (for example "Dark mode settings" inside "Display & brightness") are searchable.
 *
 * The Settings app declares its pages as preference XML resources. They are read straight from the
 * installed Settings APK through [PackageManager.getResourcesForApplication], starting from the
 * top-level screen and following every preference that opens a sub page. Each result is mapped to the
 * closest page that a third-party app is allowed to open, together with the preference key so that
 * Settings scrolls to and highlights the matching row.
 */
internal class SystemSettingsIndexer(
    private val context: Context,
) {
    private val packageManager = context.packageManager
    private val resolvableComponents = mutableMapOf<ComponentName, Boolean>()

    fun loadIndex(): List<DeviceSetting> {
        val settingsHome = resolveSettingsHome() ?: return emptyList()
        val locales = Resources.getSystem().configuration.toLocaleTags()

        // Startup loading and the first search can both ask for the index; build it only once.
        synchronized(indexLock) {
            memo?.takeIf { it.isValid(locales) }?.let { return it.settings }

            val index =
                readCache()?.takeIf { it.isValid(locales) }
                    ?: runCatching {
                        val startedAt = SystemClock.elapsedRealtime()
                        buildIndex(settingsHome, locales).also {
                            Log.i(TAG, "Indexed ${it.settings.size} settings in ${SystemClock.elapsedRealtime() - startedAt} ms")
                        }
                    }.onFailure { Log.w(TAG, "Unable to index system settings", it) }
                        .getOrNull()
                        ?.also(::writeCache)
                    ?: return emptyList()

            memo = index
            return index.settings
        }
    }

    private fun resolveSettingsHome(): ActivityInfo? =
        packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo

    private fun buildIndex(
        settingsHome: ActivityInfo,
        locales: String,
    ): SettingsIndex {
        val models = mutableMapOf<String, PackageModel?>()

        fun model(packageName: String): PackageModel? = models.getOrPut(packageName) { createPackageModel(packageName) }

        val settingsModel = model(settingsHome.packageName) ?: return SettingsIndex(emptyList(), emptyMap(), locales)
        val homeTarget = LaunchTarget(ComponentName(settingsHome.packageName, settingsHome.name), Settings.ACTION_SETTINGS)

        val results = LinkedHashMap<String, DeviceSetting>()
        val resultIds = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<QueuedScreen>()
        settingsModel.topLevelScreens().forEach { screen ->
            queue.add(QueuedScreen(settingsModel, screen, homeTarget, emptyList()))
        }

        while (queue.isNotEmpty() && visited.size < MAX_SCREENS) {
            val current = queue.removeFirst()
            if (!visited.add("${current.model.packageName}/${current.screen.name}")) continue

            val description = current.breadcrumb.joinToString(BREADCRUMB_SEPARATOR).ifBlank { settingsModel.appLabel }
            current.model.parseScreen(current.screen).forEach { pref ->
                val explicitTarget = explicitTarget(current.model, pref, ::model)
                val fallbackTarget = current.host.highlighting(pref.key)

                val childScreens = childScreens(current.model, current.screen, pref, ::model)
                val childTargets =
                    childScreens.map { (childModel, child) ->
                        val childHost =
                            explicitTarget
                                ?: childModel.activitiesByLabel[child.titleRes]?.let(::launchTargetFor)
                                ?: fallbackTarget
                        queue.add(
                            QueuedScreen(childModel, child, childHost, current.breadcrumb + pref.title.orEmpty()),
                        )
                        childHost
                    }

                if (!pref.isSearchableSetting()) return@forEach
                val target = explicitTarget ?: childTargets.firstOrNull { it.isDirect } ?: fallbackTarget
                val setting = target.toDeviceSetting(current, pref, description)
                if (setting.id in resultIds) return@forEach
                if (results.putIfAbsent("${setting.title}\u0000${setting.description}", setting) == null) {
                    resultIds.add(setting.id)
                }
            }
        }

        val packageVersions = models.values.filterNotNull().associate { it.packageName to it.versionStamp }
        return SettingsIndex(results.values.toList(), packageVersions, locales)
    }

    private fun childScreens(
        model: PackageModel,
        parent: ScreenInfo,
        pref: PrefNode,
        modelFor: (String) -> PackageModel?,
    ): List<Pair<PackageModel, ScreenInfo>> {
        if (pref.fragment == null && pref.intent == null) return emptyList()

        val externalPackage = pref.intent?.targetPackage?.takeIf { it != model.packageName && isInstalled(it) }
        if (externalPackage != null) {
            val externalModel = modelFor(externalPackage) ?: return emptyList()
            val title = pref.title ?: return emptyList()
            return externalModel.preferredScreens(externalModel.screensByTitle[title].orEmpty()).map { externalModel to it }
        }

        if (pref.titleRes == 0) return emptyList()
        val candidates = model.screensByTitleRes[pref.titleRes].orEmpty().filter { it.name != parent.name }
        return model.preferredScreens(candidates).map { model to it }
    }

    private fun explicitTarget(
        model: PackageModel,
        pref: PrefNode,
        modelFor: (String) -> PackageModel?,
    ): LaunchTarget? {
        pref.intent?.let { spec -> resolveIntentTarget(model, spec, modelFor)?.let { return it } }
        return pref.fragment?.let { model.activitiesByFragment[it] }?.let(::launchTargetFor)
    }

    private fun resolveIntentTarget(
        model: PackageModel,
        spec: IntentSpec,
        modelFor: (String) -> PackageModel?,
    ): LaunchTarget? {
        val targetPackage = spec.targetPackage?.takeUnless { it.startsWith("\${") } ?: model.packageName
        if (spec.targetClass != null) {
            val activity =
                modelFor(targetPackage)?.activitiesByName?.get(spec.targetClass)
                    ?: return null
            return launchTargetFor(activity, spec.action)
        }
        val action = spec.action ?: return null
        val intent = Intent(action).setPackage(targetPackage)
        val activity = packageManager.resolveActivity(intent, 0)?.activityInfo ?: return null
        return activity.takeIf(::isLaunchable)?.let { launchTargetFor(it, action) }
    }

    private fun launchTargetFor(
        activity: ActivityInfo,
        action: String? = null,
    ): LaunchTarget = LaunchTarget(ComponentName(activity.packageName, activity.name), action)

    private fun isInstalled(packageName: String): Boolean =
        runCatching { packageManager.getApplicationInfo(packageName, 0).enabled }.getOrDefault(false)

    private fun isLaunchable(activity: ActivityInfo): Boolean {
        if (!activity.exported) return false
        val permission = activity.permission
        if (permission != null && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        // Some vendors flag components as disabled while still resolving and launching them (ColorOS
        // does this for developer options), so trust the resolver rather than the enabled flags.
        val component = ComponentName(activity.packageName, activity.name)
        return resolvableComponents.getOrPut(component) {
            runCatching { packageManager.resolveActivity(Intent().setComponent(component), 0) != null }
                .getOrDefault(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun createPackageModel(packageName: String): PackageModel? =
        runCatching {
            val packageInfo =
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA or
                        PackageManager.MATCH_DISABLED_COMPONENTS,
                )
            val appInfo = packageInfo.applicationInfo ?: return null
            val resources = packageManager.getResourcesForApplication(appInfo)
            val activities = packageInfo.activities.orEmpty().filter(::isLaunchable)
            PackageModel(
                packageName = packageName,
                appLabel = appInfo.loadLabel(packageManager).toString(),
                versionStamp = versionStamp(packageName) ?: return null,
                resources = resources,
                screens = readScreens(resources, appInfo),
                activities = activities,
            )
        }.onFailure { Log.w(TAG, "Unable to read settings from $packageName", it) }
            .getOrNull()

    /** Finds every preference XML resource of the package and reads its root element. */
    private fun readScreens(
        resources: Resources,
        appInfo: ApplicationInfo,
    ): List<ScreenInfo> {
        val packageIds = listOf(0x7f, appInfo.labelRes ushr 24).filter { it > 1 }.distinct()
        for (packageId in packageIds) {
            val typeId = findXmlTypeId(resources, packageId) ?: continue
            val screens = mutableListOf<ScreenInfo>()
            var missing = 0
            var entry = 0
            while (missing < MAX_CONSECUTIVE_MISSING_ENTRIES && entry <= 0xffff) {
                val resId = resourceId(packageId, typeId, entry++)
                val name = runCatching { resources.getResourceEntryName(resId) }.getOrNull()
                if (name == null) {
                    missing++
                    continue
                }
                missing = 0
                readScreenRoot(resources, resId, name)?.let(screens::add)
            }
            return screens
        }
        return emptyList()
    }

    private fun findXmlTypeId(
        resources: Resources,
        packageId: Int,
    ): Int? =
        (1..MAX_RESOURCE_TYPE_ID).firstOrNull { typeId ->
            (0 until TYPE_PROBE_ENTRY_COUNT).any { entry ->
                runCatching { resources.getResourceTypeName(resourceId(packageId, typeId, entry)) }.getOrNull() == "xml"
            }
        }

    private fun readScreenRoot(
        resources: Resources,
        resId: Int,
        name: String,
    ): ScreenInfo? =
        runCatching {
            val parser = resources.getXml(resId)
            try {
                while (parser.next().let { it != XmlPullParser.START_TAG && it != XmlPullParser.END_DOCUMENT }) Unit
                if (parser.eventType != XmlPullParser.START_TAG || !parser.name.endsWith("PreferenceScreen")) {
                    return@runCatching null
                }
                val attributes = parser.readAttributes(resources)
                ScreenInfo(name, resId, attributes.title, attributes.titleRes)
            } finally {
                parser.close()
            }
        }.getOrNull()

    private fun versionStamp(packageName: String): String? =
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            "$versionCode:${info.lastUpdateTime}"
        }.getOrNull()

    private fun SettingsIndex.isValid(locales: String): Boolean =
        this.locales == locales && packageVersions.isNotEmpty() &&
            packageVersions.all { (packageName, stamp) -> versionStamp(packageName) == stamp }

    private fun cacheFile(): File = File(context.cacheDir, CACHE_FILE_NAME)

    private fun readCache(): SettingsIndex? =
        runCatching {
            val file = cacheFile()
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            if (json.optInt("format") != INDEX_FORMAT_VERSION) return null
            if (json.optLong("appVersion") != BuildConfig.VERSION_CODE.toLong()) return null
            if (json.optInt("sdk") != Build.VERSION.SDK_INT) return null
            val packages = json.getJSONObject("packages")
            val settings = json.getJSONArray("settings")
            SettingsIndex(
                settings = (0 until settings.length()).map { settings.getJSONObject(it).toDeviceSetting() },
                packageVersions = packages.keys().asSequence().associateWith { packages.getString(it) },
                locales = json.getString("locales"),
            )
        }.onFailure { Log.w(TAG, "Ignoring unreadable settings index cache", it) }
            .getOrNull()

    private fun writeCache(index: SettingsIndex) {
        runCatching {
            val json =
                JSONObject()
                    .put("format", INDEX_FORMAT_VERSION)
                    .put("appVersion", BuildConfig.VERSION_CODE.toLong())
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("locales", index.locales)
                    .put("packages", JSONObject(index.packageVersions))
                    .put("settings", JSONArray(index.settings.map { it.toJson() }))
            cacheFile().writeText(json.toString())
        }.onFailure { Log.w(TAG, "Unable to cache settings index", it) }
    }

    private inner class PackageModel(
        val packageName: String,
        val appLabel: String,
        val versionStamp: String,
        val resources: Resources,
        screens: List<ScreenInfo>,
        activities: List<ActivityInfo>,
    ) {
        private val screensByName = screens.associateBy { it.name }
        val screensByTitleRes = screens.filter { it.titleRes != 0 }.groupBy { it.titleRes }
        val screensByTitle = screens.filter { !it.title.isNullOrBlank() }.groupBy { it.title!! }
        val activitiesByName = activities.associateBy { it.name }
        val activitiesByLabel =
            activities
                .filter { it.labelRes != 0 }
                .sortedByDescending { it.metaData?.containsKey(FRAGMENT_CLASS_META_DATA) == true }
                .distinctBy { it.labelRes }
                .associateBy { it.labelRes }
        val activitiesByFragment =
            activities
                .mapNotNull { activity -> activity.metaData?.getString(FRAGMENT_CLASS_META_DATA)?.let { it to activity } }
                .distinctBy { it.first }
                .toMap()

        /**
         * Vendor builds keep the stock AOSP screens next to their own variants
         * (for example `top_level_settings` and `top_level_settings_oplus`).
         */
        private val vendorSuffix: String? =
            screensByName.keys
                .filter { it.startsWith("${TOP_LEVEL_SCREEN_PREFIX}_") && it !in AOSP_TOP_LEVEL_SCREENS }
                .map { it.substringAfterLast('_') }
                .firstOrNull()

        fun topLevelScreens(): List<ScreenInfo> {
            val vendorTopLevel = vendorSuffix?.let { screensByName["${TOP_LEVEL_SCREEN_PREFIX}_$it"] }
            if (vendorTopLevel != null) return listOf(vendorTopLevel)
            return AOSP_TOP_LEVEL_SCREENS.mapNotNull { screensByName[it] }
        }

        fun preferredScreens(candidates: List<ScreenInfo>): List<ScreenInfo> {
            if (candidates.size <= 1 || vendorSuffix == null) return candidates
            return candidates.filter { it.name.contains(vendorSuffix) }.ifEmpty { candidates }
        }

        fun parseScreen(screen: ScreenInfo): List<PrefNode> =
            runCatching {
                val nodes = mutableListOf<PrefNode>()
                val stack = ArrayDeque<PrefNode?>()
                val parser = resources.getXml(screen.xmlRes)
                try {
                    var isRoot = true
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        when (parser.eventType) {
                            XmlPullParser.START_TAG -> {
                                val tag = parser.name
                                if (isRoot) {
                                    isRoot = false
                                    stack.addLast(null)
                                    continue
                                }
                                if (tag == "intent") {
                                    stack.lastOrNull()?.let { it.intent = parser.readIntent(resources) }
                                    stack.addLast(null)
                                    continue
                                }
                                if (tag == "extra") {
                                    stack.addLast(null)
                                    continue
                                }
                                val attributes = parser.readAttributes(resources)
                                val node = PrefNode(tag, attributes)
                                nodes.add(node)
                                stack.addLast(node)
                            }

                            XmlPullParser.END_TAG -> stack.removeLastOrNull()
                        }
                    }
                } finally {
                    parser.close()
                }
                nodes
            }.onFailure { Log.w(TAG, "Unable to parse ${screen.name} in $packageName", it) }
                .getOrDefault(emptyList())
    }

    private fun LaunchTarget.toDeviceSetting(
        screen: QueuedScreen,
        pref: PrefNode,
        description: String,
    ): DeviceSetting =
        DeviceSetting(
            id = "$ID_PREFIX${screen.model.packageName}/${screen.screen.name}/${pref.key ?: pref.title}",
            title = pref.title.orEmpty(),
            description = description,
            keywords = pref.keywords,
            action = action ?: Intent.ACTION_MAIN,
            componentName = component.flattenToString(),
            highlightKey = highlightKey,
        )

    private data class SettingsIndex(
        val settings: List<DeviceSetting>,
        val packageVersions: Map<String, String>,
        val locales: String,
    )

    private data class ScreenInfo(
        val name: String,
        val xmlRes: Int,
        val title: String?,
        val titleRes: Int,
    )

    private data class QueuedScreen(
        val model: PackageModel,
        val screen: ScreenInfo,
        val host: LaunchTarget,
        val breadcrumb: List<String>,
    )

    /**
     * Where a result opens. [isDirect] is false when the result's own page cannot be opened by
     * third-party apps and the closest reachable ancestor page is used instead.
     */
    private data class LaunchTarget(
        val component: ComponentName,
        val action: String? = null,
        val highlightKey: String? = null,
        val isDirect: Boolean = true,
    ) {
        fun highlighting(key: String?): LaunchTarget =
            if (isDirect) copy(highlightKey = key, isDirect = false) else this
    }

    private data class IntentSpec(
        val action: String?,
        val targetPackage: String?,
        val targetClass: String?,
    )

    private class PrefAttributes(
        val key: String?,
        val title: String?,
        val titleRes: Int,
        val keywords: String?,
        val fragment: String?,
        val searchable: Boolean,
    )

    private class PrefNode(
        val tag: String,
        attributes: PrefAttributes,
    ) {
        val key = attributes.key
        val title = attributes.title?.trim()?.takeIf { it.isNotEmpty() }
        val titleRes = attributes.titleRes
        val fragment = attributes.fragment
        val searchable = attributes.searchable
        val keywords =
            attributes.keywords
                ?.split(KEYWORD_SEPARATOR_REGEX)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it != title }
                .orEmpty()
        var intent: IntentSpec? = null

        fun isSearchableSetting(): Boolean =
            searchable &&
                title != null &&
                title.length <= MAX_TITLE_LENGTH &&
                NON_SETTING_TAG_MARKERS.none { tag.contains(it) }
    }

    private companion object {
        private val indexLock = Any()

        private var memo: SettingsIndex? = null

        private fun resourceId(
            packageId: Int,
            typeId: Int,
            entry: Int,
        ): Int = (packageId shl 24) or (typeId shl 16) or entry

        private fun android.content.res.Configuration.toLocaleTags(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locales.toLanguageTags()
            } else {
                @Suppress("DEPRECATION")
                locale.toLanguageTag()
            }

        private fun XmlResourceParser.readAttributes(resources: Resources): PrefAttributes {
            var key: String? = null
            var title: String? = null
            var titleRes = 0
            var keywords: String? = null
            var fragment: String? = null
            var searchable = true
            for (index in 0 until attributeCount) {
                when (getAttributeName(index)) {
                    "key" -> key = readString(index, resources)
                    "title" -> {
                        titleRes = getAttributeResourceValue(index, 0)
                        title = readString(index, resources)
                    }
                    "keywords" -> keywords = readString(index, resources)
                    "fragment" -> fragment = readString(index, resources)
                    "searchable" -> searchable = getAttributeBooleanValue(index, true)
                }
            }
            return PrefAttributes(key, title, titleRes, keywords, fragment, searchable)
        }

        private fun XmlResourceParser.readIntent(resources: Resources): IntentSpec {
            var action: String? = null
            var targetPackage: String? = null
            var targetClass: String? = null
            for (index in 0 until attributeCount) {
                when (getAttributeName(index)) {
                    "action" -> action = readString(index, resources)
                    "targetPackage" -> targetPackage = readString(index, resources)
                    "targetClass" -> targetClass = readString(index, resources)
                }
            }
            return IntentSpec(action, targetPackage, targetClass)
        }

        private fun XmlResourceParser.readString(
            index: Int,
            resources: Resources,
        ): String? {
            val resId = getAttributeResourceValue(index, 0)
            if (resId != 0) return runCatching { resources.getString(resId) }.getOrNull()
            return getAttributeValue(index)?.takeUnless { it.startsWith("@") || it.startsWith("?") }
        }

        private fun DeviceSetting.toJson(): JSONObject =
            JSONObject()
                .put("id", id)
                .put("title", title)
                .put("description", description)
                .put("keywords", JSONArray(keywords))
                .put("action", action)
                .put("component", componentName)
                .put("highlightKey", highlightKey)

        private fun JSONObject.toDeviceSetting(): DeviceSetting {
            val keywords = getJSONArray("keywords")
            return DeviceSetting(
                id = getString("id"),
                title = getString("title"),
                description = optString("description").takeIf { it.isNotEmpty() },
                keywords = (0 until keywords.length()).map { keywords.getString(it) },
                action = getString("action"),
                componentName = optString("component").takeIf { it.isNotEmpty() },
                highlightKey = optString("highlightKey").takeIf { it.isNotEmpty() },
            )
        }
    }
}
