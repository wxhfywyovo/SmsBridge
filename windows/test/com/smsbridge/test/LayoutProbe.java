package com.smsbridge.test;

import com.smsbridge.pc.Config;
import com.smsbridge.pc.ui.Ui;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.SwingUtilities;

/** 调试用：打印界面组件实际位置，检查布局是否溢出。 */
public final class LayoutProbe {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("smsbridge-layout-");
        Config config = new Config(home);
        Ui[] uiRef = new Ui[1];
        SwingUtilities.invokeAndWait(() -> {
            Ui ui = new Ui(null, config);
            ui.demo();
            uiRef[0] = ui;
            ui.showWindow();
        });
        Thread.sleep(700);
        SwingUtilities.invokeAndWait(() -> dump(uiRef[0].frame().getContentPane(), 0));
        System.exit(0);
    }

    private static void dump(Component component, int depth) {
        String indent = "  ".repeat(depth);
        String name = component.getClass().getSimpleName();
        String text = "";
        if (component instanceof javax.swing.JLabel label) {
            text = " text=" + label.getText().replace("\n", " ").substring(0,
                    Math.min(24, label.getText().length()));
        }
        System.out.println(indent + name + " " + component.getBounds()
                + " pref=" + component.getPreferredSize() + text);
        if (depth < 8 && component instanceof Container container) {
            for (Component child : container.getComponents()) {
                dump(child, depth + 1);
            }
        }
    }
}
