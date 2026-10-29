package com.openpro.camera

/**
 * Every camera GoPro Quik 13.43 knows (com.gopro.entity.camera.CameraModel), with the features the
 * Camera models page shows as chips, and developer notes on each (never shown in the app).
 *
 * HERO (2024) is tested. The cameras on GoPro's Open GoPro list are enabled but untested: they
 * speak the same Bluetooth protocol (service FEA6) and Wi-Fi access point as HERO (2024), and
 * serve the same media endpoints (Quik uses gp/gpMediaList, gp/gpMediaMetadata and
 * videos/DCIM for every model), so OpenPro should work with them as it is. The developer notes describe
 * each camera; docs/MODEL_SUPPORT.md (outside the app repo) says what supporting the rest would take.
 */
enum class Support {
    Supported,
    /** Enabled: same protocol as a supported camera, but nobody has tried it yet. */
    Untested,
    Planned,
    Legacy,
    ;

    val usable get() = this == Supported || this == Untested
}

/** What a camera offers in OpenPro, shown as chips on the Camera models page. [Spherical] is the camera's own. */
enum class Feature(val label: String) {
    Spherical("360"),
    Stabilization("Stabilization"),
    HorizonLeveling("Horizon leveling"),
    LivePreview("Live preview"),
}

data class CameraModel(
    /** Model ID the camera reports in BLE "Get Hardware Info" (Quik's modelNumber). */
    val modelId: Int,
    /** Firmware/model string prefix, e.g. "H24.03". */
    val modelString: String,
    val displayName: String,
    val codename: String,
    val year: Int,
    val support: Support,
    /** For developers (what Quik does with it, what supporting it would take); never shown in the app. */
    val devNotes: String,
    val features: Set<Feature> = emptySet(),
)

object CameraModels {
    private const val OPEN_GOPRO_NOTE =
        "Open GoPro BLE (service FEA6) + WiFi AP HTTP on 10.5.5.9:8080. Same media endpoints as HERO (2024)."
    private const val CORI_NOTE =
        "Writes CORI/IORI/GRAV orientation to GPMF; Quik offers HorizonLeveling (av::Stabilizer meldCoriIoriGrav)."
    private const val LEGACY_NOTE =
        "Pre-Open GoPro: WiFi-only gpControl API; no CORI/IORI, so Quik has no in-app stabilization for it."

    /** Open GoPro HERO cameras: live preview, and the engine stabilizes and levels their clips. */
    private val HERO_FEATURES = setOf(Feature.LivePreview, Feature.Stabilization, Feature.HorizonLeveling)

    val all: List<CameraModel> = listOf(
        CameraModel(1, "HD2.01", "HERO2", "HERO_2", 2011, Support.Legacy, LEGACY_NOTE + " Needs WiFi BacPac."),
        CameraModel(2, "HD3.01", "HERO3 White", "SHORES", 2012, Support.Legacy, LEGACY_NOTE),
        CameraModel(3, "HD3.02", "HERO3 Silver", "BLACKS", 2012, Support.Legacy, LEGACY_NOTE),
        CameraModel(4, "HD3.03", "HERO3 Black", "TODOS", 2012, Support.Legacy, LEGACY_NOTE),
        CameraModel(10, "HD3.10", "HERO3+ Silver", "ULUWATU", 2013, Support.Legacy, LEGACY_NOTE),
        CameraModel(11, "HD3.11", "HERO3+ Black", "BAWA", 2013, Support.Legacy, LEGACY_NOTE),
        CameraModel(12, "HD4.01", "HERO4 Silver", "BACKDOOR", 2014, Support.Legacy, LEGACY_NOTE),
        CameraModel(13, "HD4.02", "HERO4 Black", "PIPE", 2014, Support.Legacy, LEGACY_NOTE),
        CameraModel(14, "HD3.20", "HERO (2014)", "BOLINAS", 2014, Support.Legacy, LEGACY_NOTE),
        CameraModel(15, "HD3.21", "HERO+ LCD", "HALEIWA", 2015, Support.Legacy, LEGACY_NOTE),
        CameraModel(16, "HX1.01", "HERO4 Session", "ROCKY_POINT", 2015, Support.Legacy, LEGACY_NOTE),
        CameraModel(17, "HD3.22", "HERO+", "HIMALAYAS", 2015, Support.Legacy, LEGACY_NOTE),
        CameraModel(19, "HD5.02", "HERO5 Black", "STREAKY", 2016, Support.Legacy,
            "GPMF gyro/accel, no CORI. Engine has ReelSteady config for it (rsgo configure_rotation_settings_for_hero5')."),
        CameraModel(21, "HD5.03", "HERO5 Session", "MARGARET_RIVER", 2016, Support.Legacy,
            "GPMF gyro/accel, no CORI. WiFi gpControl."),
        CameraModel(22, "FS1.04", "Fusion", "SUPERBANK", 2017, Support.Legacy,
            "Dual-file spherical (front/back on two SD cards: videos/ and videos2/). Quik stitches; spherical stabilization path.", setOf(Feature.Spherical)),
        CameraModel(24, "HD6.01", "HERO6 Black", "CHOPES", 2017, Support.Legacy,
            "GPMF gyro/accel, no CORI. Engine has ReelSteady config for it (rsgo configure_rotation_settings_for_hero6')."),
        CameraModel(30, "HD7.01", "HERO7 Black", "TAVARUA", 2018, Support.Planned,
            "GPMF gyro/accel, no CORI. Engine has ReelSteady config for it (rsgo configure_rotation_settings_for_hero7')."),
        CameraModel(32, "H18.02", "HERO7 White", "BOOMER", 2018, Support.Legacy, "Legacy WiFi gpControl API. Not analysed further."),
        CameraModel(33, "H18.03", "HERO7 Silver", "BADGER", 2018, Support.Legacy, "WiFi gpControl."),
        CameraModel(34, "H18.01", "HERO (2018)", "SMOKY", 2018, Support.Legacy, "Legacy WiFi gpControl API. Not analysed further."),
        CameraModel(50, "HD8.01", "HERO8 Black", "JBAY", 2019, Support.Planned,
            "First camera with CORI/IORI/GRAV ('Will not apply horizon leveling prior to Hero8' in the engine). " +
                "BLE FEA6 control; media over WiFi gpControl endpoints. Not on GoPro's Open GoPro list."),
        CameraModel(51, "H19.03", "MAX", "COCONUTS", 2019, Support.Planned,
            "360: .360 files (EAC). Quik uses SphericalStabilization (libgopro-lib-spherical-stabilization 'fse' engine, " +
                "anti_shake_intensity + world_lock/horizon_leveling). Export OutputProjection ERP/EAC.", setOf(Feature.Spherical)),
        CameraModel(55, "HD9.01", "HERO9 Black", "BOILERS", 2020, Support.Untested,
            "$OPEN_GOPRO_NOTE $CORI_NOTE Needs firmware 1.70 or later for Open GoPro (live preview, delete, lengths).", HERO_FEATURES),
        CameraModel(57, "H21.01", "HERO10 Black", "KONGS", 2021, Support.Untested, "$OPEN_GOPRO_NOTE $CORI_NOTE", HERO_FEATURES),
        CameraModel(58, "H22.01", "HERO11 Black", "SULTANS", 2022, Support.Untested,
            "$OPEN_GOPRO_NOTE $CORI_NOTE Adds COHN (camera on home network) via BLE network-management service 0090.", HERO_FEATURES),
        CameraModel(60, "H22.03", "HERO11 Black Mini", "PISMO", 2022, Support.Untested, "$OPEN_GOPRO_NOTE $CORI_NOTE No screen.", HERO_FEATURES),
        CameraModel(62, "H23.01", "HERO12 Black", "RED_BLUFF", 2023, Support.Untested, "$OPEN_GOPRO_NOTE $CORI_NOTE", HERO_FEATURES),
        CameraModel(65, "H24.01", "HERO13 Black", "SHIPSTERN", 2024, Support.Untested,
            "$OPEN_GOPRO_NOTE $CORI_NOTE Lens mods (Anamorphic disables Quik stabilization).", HERO_FEATURES),
        CameraModel(66, "H24.03", "HERO (2024)", "FRACTION", 2024, Support.Supported,
            "Quik treats it as a 'Fraction' camera: it asks the engine for suggested_stabilization_settings and " +
                "applies AntiShake (in-app HyperSmooth, gp::algo::eis::hypersmooth) by default. Horizon-leveling tool " +
                "reports 'TemporaryForFraction'. BLE FEA6 control, WiFi AP for media.", HERO_FEATURES),
        CameraModel(70, "H25.03", "LIT HERO", "LIT_FRACTION", 2025, Support.Untested,
            "Second 'Fraction' camera: identical Quik path to HERO (2024) (CameraModel.Companion.a matches both), " +
                "including the stabilization it suggests.", HERO_FEATURES),
        CameraModel(64, "H24.02", "MAX2", "PEBBLES", 2025, Support.Untested,
            "360 camera on the Open GoPro list: browsing, saving and uploading .360 files should work. Stabilizing goes " +
                "through the engine's SphericalStabilization edit and exports equirectangular 360 video (untested on a MAX2).", setOf(Feature.Spherical, Feature.LivePreview, Feature.Stabilization)),
        CameraModel(69, "H26.01", "MISSION 1 PRO", "SANDBAR", 2026, Support.Untested,
            "On the Open GoPro list, so the same protocol as the others; not analysed beyond the model table.", setOf(Feature.LivePreview)),
        CameraModel(71, "H26.02", "MISSION 1", "SANDBAR_LITE", 2026, Support.Untested, "See MISSION 1 PRO.", setOf(Feature.LivePreview)),
        CameraModel(74, "H26.03", "MISSION 1 PRO ILS", "SANDBOX", 2026, Support.Planned,
            "Interchangeable lens. Quik marks its clips isSandBoxMedia and reports stabilization unavailable (NonGoProLens). " +
                "Not on the Open GoPro list."),
    )

    fun byModelId(id: Int): CameraModel? = all.firstOrNull { it.modelId == id }

    fun isUsable(id: Int): Boolean = byModelId(id)?.support?.usable == true

    /** What to tell the user about a camera OpenPro can't work with. */
    fun unsupportedMessage(modelId: Int): String {
        val name = byModelId(modelId)?.displayName ?: "This GoPro (model ID $modelId)"
        return "$name isn't supported by OpenPro. Camera models on the home screen lists the cameras it works with."
    }
}
