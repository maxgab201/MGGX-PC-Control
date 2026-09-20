from pathlib import Path

ROOT = Path('moonlight-android')
app_gradle = ROOT / 'app/build.gradle'
game_java = ROOT / 'app/src/main/java/com/limelight/Game.java'

s = app_gradle.read_text(encoding='utf-8')
s = s.replace('versionName "12.1"', 'versionName "3.0.0"')
s = s.replace('versionCode = 314', 'versionCode = 30000')
s = s.replace('applicationId "com.limelight"\n            dimension "root"',
              'applicationId "com.mggx.moonlightminecraft"\n            dimension "root"')
s = s.replace('applicationIdSuffix ".unofficial"', '// MGGX: dedicated application ID, no suffix')
s = s.replace('resValue "string", "app_label", "Moonlight"',
              'resValue "string", "app_label", "MGGX Moonlight Minecraft"')
s = s.replace("coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.5'",
              "coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.5'\n    testImplementation 'junit:junit:4.13.2'")
app_gradle.write_text(s, encoding='utf-8')

s = game_java.read_text(encoding='utf-8')
anchor = 'import com.limelight.binding.input.KeyboardTranslator;\n'
if 'MinecraftTouchOverlay' not in s:
    s = s.replace(anchor, anchor + 'import com.limelight.binding.input.minecraft.MinecraftTouchOverlay;\n')

field_anchor = '    private VirtualController virtualController;\n'
if 'private MinecraftTouchOverlay minecraftTouchOverlay;' not in s:
    s = s.replace(field_anchor, field_anchor + '    private MinecraftTouchOverlay minecraftTouchOverlay;\n')

insert_anchor = '''        if (prefConfig.onscreenController) {
            // create virtual onscreen controller
            virtualController = new VirtualController(controllerHandler,
                    (FrameLayout)streamView.getParent(),
                    this);
            virtualController.refreshLayout();
            virtualController.show();
        }
'''

insert_block = insert_anchor + '''
        minecraftTouchOverlay = new MinecraftTouchOverlay(this, new MinecraftTouchOverlay.InputSink() {
            @Override
            public void key(int androidKeyCode, boolean down) {
                if (conn == null || keyboardTranslator == null) return;
                short translated = keyboardTranslator.translate(androidKeyCode, -1);
                if (translated == 0) return;
                conn.sendKeyboardInput(translated,
                        down ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP,
                        (byte)0, (byte)0);
            }

            @Override
            public void mouseMove(short dx, short dy) {
                if (conn != null && (dx != 0 || dy != 0)) conn.sendMouseMove(dx, dy);
            }

            @Override
            public void mouseButton(int button, boolean down) {
                if (conn == null) return;
                byte moonButton = button == MinecraftTouchOverlay.MOUSE_RIGHT
                        ? MouseButtonPacket.BUTTON_RIGHT : MouseButtonPacket.BUTTON_LEFT;
                if (down) conn.sendMouseButtonDown(moonButton);
                else conn.sendMouseButtonUp(moonButton);
            }

            @Override
            public void scroll(short amount) {
                if (conn != null) conn.sendMouseHighResScroll(amount);
            }
        });
        FrameLayout.LayoutParams minecraftOverlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        ((FrameLayout)streamView.getParent()).addView(minecraftTouchOverlay, minecraftOverlayParams);
'''

if 'minecraftTouchOverlay = new MinecraftTouchOverlay' not in s:
    if insert_anchor not in s:
        raise SystemExit('virtual controller anchor not found')
    s = s.replace(insert_anchor, insert_block)

game_java.write_text(s, encoding='utf-8')

java_dir = ROOT / 'app/src/main/java/com/limelight/binding/input/minecraft'
java_dir.mkdir(parents=True, exist_ok=True)
(java_dir / 'MinecraftTouchOverlay.java').write_text(Path('builder/MinecraftTouchOverlay.java').read_text(encoding='utf-8'), encoding='utf-8')
(java_dir / 'MinecraftControlMath.java').write_text(Path('builder/MinecraftControlMath.java').read_text(encoding='utf-8'), encoding='utf-8')

test_dir = ROOT / 'app/src/test/java/com/limelight/binding/input/minecraft'
test_dir.mkdir(parents=True, exist_ok=True)
(test_dir / 'MinecraftControlMathTest.java').write_text(Path('builder/MinecraftControlMathTest.java').read_text(encoding='utf-8'), encoding='utf-8')

print('Moonlight patched successfully')
