import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class BuildContractTests(unittest.TestCase):
    def test_native_builds_use_the_root_web_app(self):
        android_build = (ROOT / "android" / "app" / "build.gradle").read_text(encoding="utf-8")
        ios_project = (ROOT / "ios" / "MonPlanificateur.xcodeproj" / "project.pbxproj").read_text(encoding="utf-8")

        self.assertIn("rootProject.file('../index.html')", android_build)
        self.assertIn("dependsOn tasks.named('syncPlannerWebAssets')", android_build)
        self.assertFalse((ROOT / "android" / "app" / "src" / "main" / "assets" / "index.html").exists())
        self.assertIn('inputPaths = ("$(SRCROOT)/../index.html", );', ios_project)
        self.assertIn(r'cp \"$source\" \"$destination\"', ios_project)
        self.assertFalse((ROOT / "ios" / "MonPlanificateur" / "Web" / "index.html").exists())

    def test_reminder_sound_is_preserved_by_both_native_paths(self):
        android_source = (ROOT / "android" / "app" / "src" / "main" / "java" / "ci" / "monplanificateur" / "MainActivityCore.java").read_text(encoding="utf-8")
        ios_source = (ROOT / "ios" / "MonPlanificateur" / "PlannerViewController.swift").read_text(encoding="utf-8")

        self.assertIn('AlarmReceiver.schedule(this, date, eventId, time, title, sound)', android_source)
        self.assertIn('row["sound"] as? Bool ?? true, when)', ios_source)
        self.assertIn('content.sound = sound ? .default : nil', ios_source)
        self.assertIn('for (date, id, time, title, sound, when) in valid', ios_source)

    def test_android_manifest_activity_is_the_kotlin_entry_point(self):
        android_root = ROOT / "android"
        manifest = (android_root / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
        activity = (android_root / "app" / "src" / "main" / "java" / "ci" / "monplanificateur" / "MainActivity.kt").read_text(encoding="utf-8")
        implementation = (android_root / "app" / "src" / "main" / "java" / "ci" / "monplanificateur" / "MainActivityCore.java").read_text(encoding="utf-8")
        build = (android_root / "app" / "build.gradle").read_text(encoding="utf-8")

        self.assertIn('android:name=".MainActivity"', manifest)
        self.assertIn("class MainActivity : MainActivityCore()", activity)
        self.assertIn("public class MainActivityCore extends Activity", implementation)
        self.assertIn("org.jetbrains.kotlin.android", build)
        self.assertIn("versionName '1.6.5'", build)

    def test_android_quick_add_shortcut_and_haptic_feedback(self):
        android_root = ROOT / "android"
        manifest = (android_root / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
        shortcuts = (android_root / "app" / "src" / "main" / "res" / "xml" / "shortcuts.xml").read_text(encoding="utf-8")
        implementation = (android_root / "app" / "src" / "main" / "java" / "ci" / "monplanificateur" / "MainActivityCore.java").read_text(encoding="utf-8")
        html = (ROOT / "index.html").read_text(encoding="utf-8")

        self.assertIn('android:name="android.app.shortcuts"', manifest)
        self.assertIn('android:action="ci.monplanificateur.ADD_TASK"', shortcuts)
        self.assertIn("ACTION_QUICK_ADD.equals(intent.getAction())", implementation)
        self.assertIn("document.getElementById('quickAddBtn')?.click()", implementation)
        self.assertIn("HapticFeedbackConstants.KEYBOARD_TAP", implementation)
        self.assertIn("document.documentElement.classList.add('android-native')", html)
        self.assertIn(".android-native #plannerNav .nav-item{min-width:0;min-height:48px", html)
        self.assertIn('id="toastMessage" role="status" aria-live="polite"', html)


if __name__ == "__main__":
    unittest.main()
