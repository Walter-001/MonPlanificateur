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
        android_source = (ROOT / "android" / "app" / "src" / "main" / "java" / "ci" / "monplanificateur" / "MainActivity.java").read_text(encoding="utf-8")
        ios_source = (ROOT / "ios" / "MonPlanificateur" / "PlannerViewController.swift").read_text(encoding="utf-8")

        self.assertIn('AlarmReceiver.schedule(this, date, eventId, time, title, sound)', android_source)
        self.assertIn('row["sound"] as? Bool ?? true, when)', ios_source)
        self.assertIn('content.sound = sound ? .default : nil', ios_source)
        self.assertIn('for (date, id, time, title, sound, when) in valid', ios_source)


if __name__ == "__main__":
    unittest.main()
