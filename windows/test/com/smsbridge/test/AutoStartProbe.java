package com.smsbridge.test;

import com.smsbridge.pc.AutoStart;

public final class AutoStartProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("cmd=" + AutoStart.commandLine());
        System.out.println("set(true)=" + AutoStart.set(true));
        Thread.sleep(300);
        System.out.println("isEnabled=" + AutoStart.isEnabled());
        System.out.println("set(false)=" + AutoStart.set(false));
        Thread.sleep(300);
        System.out.println("isEnabled=" + AutoStart.isEnabled());
    }
}
