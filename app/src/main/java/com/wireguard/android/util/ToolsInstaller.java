/*
 * Copyright © 2018 Samuel Holland <samuel@sholland.org>
 * Copyright © 2018 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package com.wireguard.android.util;

import android.content.Context;
import android.system.OsConstants;
import android.util.Log;

import com.wireguard.android.Application;
import com.wireguard.android.BuildConfig;
import com.wireguard.android.util.RootShell.NoRootException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Helper to install WireGuard tools to the system partition.
 */

public final class ToolsInstaller {
    private static final String[][] EXECUTABLES = {
            {"libwg.so", "wg"},
            {"libwg-quick.so", "wg-quick"},
    };
    private static final File[] INSTALL_DIRS = {
            new File("/system/xbin"),
            new File("/system/bin"),
    };
    private static final File INSTALL_DIR = getInstallDir();
    private static final String TAG = "WireGuard/" + ToolsInstaller.class.getSimpleName();

    private final File localBinaryDir;
    private final Object lock = new Object();
    private final File nativeLibraryDir;
    private Boolean areToolsAvailable;
    private Boolean installAsMagiskModule;

    public ToolsInstaller(final Context context) {
        localBinaryDir = new File(context.getCacheDir(), "bin");
        nativeLibraryDir = new File(context.getApplicationInfo().nativeLibraryDir);
    }

    private static File getInstallDir() {
        final String path = System.getenv("PATH");
        if (path == null)
            return INSTALL_DIRS[0];
        final List<String> paths = Arrays.asList(path.split(":"));
        for (final File dir : INSTALL_DIRS) {
            if (paths.contains(dir.getPath()) && dir.isDirectory())
                return dir;
        }
        return null;
    }

    public int areInstalled() throws NoRootException {
        willInstallAsMagiskModule(true);
        if (INSTALL_DIR == null)
            return OsConstants.ENOENT;
        final StringBuilder script = new StringBuilder();
        for (final String[] names : EXECUTABLES) {
            script.append(String.format("cmp -s '%s' '%s' && ",
                    new File(nativeLibraryDir, names[0]),
                    new File(INSTALL_DIR, names[1])));
        }
        script.append("exit ").append(OsConstants.EALREADY).append(';');
        try {
            return Application.getRootShell().run(null, script.toString());
        } catch (final IOException ignored) {
            return OsConstants.EXIT_FAILURE;
        }
    }

    public void ensureToolsAvailable() throws FileNotFoundException, NoRootException {
        synchronized (lock) {
            if (areToolsAvailable == null) {
                final int ret = symlink();
                if (ret == OsConstants.EALREADY) {
                    Log.d(TAG, "Tools were already symlinked into our private binary dir");
                    areToolsAvailable = true;
                } else if (ret == OsConstants.EXIT_SUCCESS) {
                    Log.d(TAG, "Tools are now symlinked into our private binary dir");
                    areToolsAvailable = true;
                } else {
                    Log.e(TAG, "For some reason, wg and wg-quick are not available at all");
                    areToolsAvailable = false;
                }
            }
            if (!areToolsAvailable)
                throw new FileNotFoundException("Required tools unavailable");
        }
    }

    public boolean willInstallAsMagiskModule(boolean checkForIt) {
        synchronized (lock) {
            if (installAsMagiskModule == null) {
                if (!checkForIt)
                    throw new RuntimeException("Expected to already know whether this is a Magisk system");
                try {
                    installAsMagiskModule = Application.getRootShell().run(null, "[ -d /sbin/.core/mirror -a -d /sbin/.core/img -a ! -f /cache/.disable_magisk ]") == OsConstants.EXIT_SUCCESS;
                } catch (final Exception ignored) {
                    installAsMagiskModule = false;
                }
            }
            return installAsMagiskModule;
        }
    }

    private int installSystem() throws NoRootException {
        if (INSTALL_DIR == null)
            return OsConstants.ENOENT;
        final StringBuilder script = new StringBuilder("set -ex; ");
        script.append("trap 'mount -o ro,remount /system' EXIT; mount -o rw,remount /system; ");
        for (final String[] names : EXECUTABLES) {
            final File destination = new File(INSTALL_DIR, names[1]);
            script.append(String.format("cp '%s' '%s'; chmod 755 '%s'; restorecon '%s' || true; ",
                    new File(nativeLibraryDir, names[0]), destination, destination, destination));
        }
        try {
            return Application.getRootShell().run(null, script.toString());
        } catch (final IOException ignored) {
            return OsConstants.EXIT_FAILURE;
        }
    }

    private int installMagisk() throws NoRootException {
        final StringBuilder script = new StringBuilder("set -ex; ");

        script.append("trap 'rm -rf /sbin/.core/img/wireguard' INT TERM EXIT; ");
        script.append(String.format("rm -rf /sbin/.core/img/wireguard/; mkdir -p /sbin/.core/img/wireguard%s; ", INSTALL_DIR));
        script.append(String.format("printf 'name=WireGuard Command Line Tools\nversion=%s\nversionCode=%s\nauthor=zx2c4\ndescription=Command line tools for WireGuard\nminMagisk=1500\n' > /sbin/.core/img/wireguard/module.prop; ", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        script.append("touch /sbin/.core/img/wireguard/auto_mount; ");
        for (final String[] names : EXECUTABLES) {
            final File destination = new File("/sbin/.core/img/wireguard" + INSTALL_DIR, names[1]);
            script.append(String.format("cp '%s' '%s'; chmod 755 '%s'; restorecon '%s' || true; ",
                    new File(nativeLibraryDir, names[0]), destination, destination, destination));
        }
        script.append("trap - INT TERM EXIT;");

        try {
            return Application.getRootShell().run(null, script.toString());
        } catch (final IOException ignored) {
            return OsConstants.EXIT_FAILURE;
        }
    }

    public int install() throws NoRootException {
        return willInstallAsMagiskModule(true) ? installMagisk() : installSystem();
    }

    public int symlink() throws NoRootException {
        final StringBuilder script = new StringBuilder("set -x; ");
        for (final String[] names : EXECUTABLES) {
            script.append(String.format("test '%s' -ef '%s' && ",
                    new File(nativeLibraryDir, names[0]),
                    new File(localBinaryDir, names[1])));
        }
        script.append("exit ").append(OsConstants.EALREADY).append("; set -e; ");

        for (final String[] names : EXECUTABLES) {
            script.append(String.format("ln -fns '%s' '%s'; ",
                    new File(nativeLibraryDir, names[0]),
                    new File(localBinaryDir, names[1])));
        }
        script.append("exit ").append(OsConstants.EXIT_SUCCESS).append(';');

        try {
            return Application.getRootShell().run(null, script.toString());
        } catch (final IOException ignored) {
            return OsConstants.EXIT_FAILURE;
        }
    }
}
