"""Host tests of compile_filament_materials.py with fake matc compilers.

Run: `python3 -m unittest discover -s android/tools -p 'test_*.py'` from the
plugin root (no Filament download, no device).
"""
from __future__ import annotations

import contextlib
import io
import os
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import compile_filament_materials as tool  # noqa: E402


class FakeRun:
    def __init__(self, version: str = "72\n", version_code: int = 0, failing: str = ""):
        self.version, self.version_code, self.failing = version, version_code, failing
        self.calls = []

    def __call__(self, argv, **kwargs):
        self.calls.append(list(argv))
        if argv[1] == "--version":
            return subprocess.CompletedProcess(argv, self.version_code, self.version, "")
        code = 1 if self.failing and argv[-1].endswith(f"{self.failing}.mat") else 0
        return subprocess.CompletedProcess(argv, code)


class CompileFilamentMaterialsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.matc = os.path.join(self.temp.name, "matc")
        with open(self.matc, "w", encoding="utf-8") as handle:
            handle.write("")

    def run_main(self, run, argv=None):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code = tool.main(argv if argv is not None else ["--matc", self.matc], run=run)
        return code, err.getvalue()

    def test_every_material_source_exists_in_plugin_android_sources(self):
        for name in tool.MATERIALS:
            self.assertTrue(os.path.isfile(os.path.join(
                tool.DEFAULT_MAIN_DIR, "materials", f"{name}.mat")), name)

    def test_compiles_every_material_for_opengl_and_vulkan(self):
        run = FakeRun()
        code, err = self.run_main(run)
        self.assertEqual((code, err), (0, ""))
        compiler = os.path.abspath(self.matc)
        self.assertEqual(run.calls[0], [compiler, "--version"])
        main = tool.DEFAULT_MAIN_DIR
        self.assertEqual(run.calls[1:], [
            [compiler, "--platform", "mobile", "--api", "opengl", "--api", "vulkan",
             "--output", os.path.join(main, "assets", "materials", f"{name}.filamat"),
             os.path.join(main, "materials", f"{name}.mat")]
            for name in ("coverage_points", "coverage_cubes", "camera_stream_passthrough")])

    def test_wrong_format_compiles_nothing(self):
        run = FakeRun(version="71\n")
        code, err = self.run_main(run)
        self.assertEqual(code, 1)
        self.assertIn("format 72; compiler reported '71'", err)
        self.assertEqual(len(run.calls), 1)

    def test_failing_version_command_is_refused(self):
        run = FakeRun(version_code=3)
        code, err = self.run_main(run)
        self.assertEqual(code, 1)
        self.assertEqual(len(run.calls), 1)

    def test_compilation_failure_names_material_and_stops(self):
        run = FakeRun(failing="coverage_points")
        code, err = self.run_main(run)
        self.assertEqual(code, 1)
        self.assertIn("Material compilation failed: coverage_points", err)
        self.assertEqual(len(run.calls), 2)

    def test_missing_compiler_is_refused_before_running(self):
        run = FakeRun()
        code, err = self.run_main(run, ["--matc", os.path.join(self.temp.name, "absent")])
        self.assertEqual(code, 1)
        self.assertIn("not found", err)
        self.assertEqual(run.calls, [])

    def test_missing_argument_is_usage_error(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            tool.main([], run=FakeRun())
        self.assertEqual(raised.exception.code, 2)

    @unittest.skipIf(os.name == "nt", "fake compiler is a POSIX script")
    def test_real_subprocess_writes_outputs_into_main_dir(self):
        main = os.path.join(self.temp.name, "main")
        os.makedirs(os.path.join(main, "materials"))
        os.makedirs(os.path.join(main, "assets", "materials"))
        log = os.path.join(self.temp.name, "argv.log")
        with open(self.matc, "w", encoding="utf-8") as handle:
            handle.write(textwrap.dedent(f"""\
                #!{sys.executable}
                import sys
                args = sys.argv[1:]
                with open({log!r}, "a") as log:
                    log.write(" ".join(args) + "\\n")
                if args == ["--version"]:
                    print("72")
                else:
                    open(args[args.index("--output") + 1], "w").write("filamat")
                """))
        os.chmod(self.matc, os.stat(self.matc).st_mode | stat.S_IXUSR)
        tool.compile_materials(tool.resolve_compiler(self.matc), main)
        for name in tool.MATERIALS:
            self.assertTrue(os.path.isfile(
                os.path.join(main, "assets", "materials", f"{name}.filamat")))
        with open(log, encoding="utf-8") as handle:
            self.assertEqual(len(handle.read().splitlines()), 1 + len(tool.MATERIALS))


if __name__ == "__main__":
    unittest.main()
