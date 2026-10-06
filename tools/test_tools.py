"""离线回归：python -B -m unittest discover -s tools -p test_tools.py。"""
import contextlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import hook_inventory as inventory


class InventoryTests(unittest.TestCase):
    def test_scope_full_snapshot_and_unique_name(self):
        temp_root = Path("D:/tmp/codex/friendlyschool-tools-tests")
        temp_root.mkdir(parents=True, exist_ok=True)
        sample = "pid=1 uid=1 name=com.target\n"
        sample += "".join(f"  0x1 void Target.m{i:03d}()\n" for i in range(405))
        sample += "pid=2 uid=1 name=com.target:remote\n  0x2 void Target.remote()\n"
        sample += "pid=3 uid=2 name=com.other\n  0x3 void Other.unrelated()\n"
        sample += "    realEntryPoint=0x0 eq=false\n"
        with tempfile.TemporaryDirectory(dir=temp_root) as folder:
            argv = ["hook_inventory.py", "--pkg", "com.target", "--outdir", folder]
            with patch.object(sys, "argv", argv), patch.object(inventory, "adb_available", return_value=True), \
                    patch.object(inventory, "dump", return_value=(sample, None)), \
                    contextlib.redirect_stdout(io.StringIO()):
                inventory.main()
                inventory.main()
            files = list(Path(folder).glob("*.txt"))
            self.assertEqual(len(files), 2, "连续运行不能覆盖基线")
            for file in files:
                data = file.read_text(encoding="utf-8")
                self.assertNotIn("Other.unrelated", data)
                self.assertIn("Target.remote()", data)
                self.assertIn("Target.m404()", data)
                self.assertIn("入口点 eq=false（= hook 未真正生效）: 0", data)

    def test_dump_failure_is_visible(self):
        with patch.object(inventory.subprocess, "run", return_value=subprocess.CompletedProcess(
                [], 1, "", "adb disconnected")):
            text, error = inventory.dump("com.target")
        self.assertFalse(text)
        self.assertIn("adb disconnected", error)

    def test_no_target_never_saves_an_unrelated_baseline(self):
        sample = "pid=3 uid=2 name=com.other\n  0x3 void Other.unrelated()\n"
        with patch.object(sys, "argv", ["hook_inventory.py", "--pkg", "com.target"]), \
                patch.object(inventory, "adb_available", return_value=True), \
                patch.object(inventory, "dump", return_value=(sample, None)), \
                patch("builtins.open") as write:
            with self.assertRaises(SystemExit):
                inventory.main()
            write.assert_not_called()


class UiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import lsposed_ui
        cls.ui = lsposed_ui

    def test_failed_dump_never_reads_or_taps(self):
        with patch.object(self.ui, "sh", return_value="ERROR: could not get idle state."), \
                patch("builtins.open") as read:
            with self.assertRaises(RuntimeError):
                self.ui.dump()
            read.assert_not_called()

    def test_subprocess_failure_is_visible(self):
        with patch.object(self.ui.subprocess, "run", return_value=subprocess.CompletedProcess(
                [], 1, "", "adb disconnected")):
            with self.assertRaises(RuntimeError):
                self.ui.sh(["adb", "shell", "uiautomator", "dump"])

    def test_exit_zero_dump_error_on_stderr_is_visible(self):
        with patch.object(self.ui.subprocess, "run", return_value=subprocess.CompletedProcess(
                [], 0, "", "ERROR: could not get idle state.")):
            with self.assertRaisesRegex(RuntimeError, "could not get idle state"):
                self.ui.dump()

    def test_xml_entities_and_invalid_bounds(self):
        nodes = self.ui.parse('<hierarchy><node text="A &amp; B" bounds="[0,0][10,20]" /></hierarchy>')
        self.assertEqual(nodes[0]["text"], "A & B")
        with patch.object(self.ui, "sh") as sh:
            with self.assertRaises(ValueError):
                self.ui.tap("bad bounds")
            sh.assert_not_called()

    def test_out_of_range_selection_never_taps(self):
        xml = '<hierarchy><node text="target" bounds="[0,0][10,20]" /></hierarchy>'
        with patch.object(sys, "argv", ["lsposed_ui.py", "tap", "target", "1"]), \
                patch.object(self.ui, "dump", return_value=xml), patch.object(self.ui, "tap") as tap:
            with self.assertRaises(ValueError):
                self.ui.main()
            tap.assert_not_called()


if __name__ == "__main__":
    unittest.main()
