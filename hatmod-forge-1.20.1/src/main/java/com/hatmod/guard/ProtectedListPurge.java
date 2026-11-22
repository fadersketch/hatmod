package com.hatmod.guard;

import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ProtectedListPurge {

    private ProtectedListPurge() {
    }

    public static void wipe(Entity target) {
        removeFromExternalBossList(target);
    }

    private static void removeFromExternalBossList(Entity target) {
        if (!target.getClass().getName().equals(
                "flashfur.omnimobs.entities.metapotent_flashfur.MetapotentFlashfurEntity")) {
            return;
        }
        try {
            Object boss = target.getClass().getField("metapotentFlashfur").get(target);
            if (boss == null) {
                return;
            }
            Class<?> levelClass = Class.forName(
                    "flashfur.omnimobs.entities.metapotent_flashfur.MetapotentFlashfurLevel");
            Object list = levelClass.getField("metapotentFlashfurList").get(null);
            if (!(list instanceof java.util.List<?> bosses)) {
                return;
            }
            if (!removeFromProtectedList(bosses, boss)) {
                return;
            }
            try {
                Object bossEvent = boss.getClass().getField("bossEvent").get(boss);
                if (bossEvent instanceof net.minecraft.server.level.ServerBossEvent event) {
                    event.removeAllPlayers();
                    event.setVisible(false);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("removal")
    private static boolean removeFromProtectedList(java.util.List<?> list, Object element) {
        int index = list.indexOf(element);
        try {
            if (index >= 0 && list.remove(element)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            Method getObject = unsafeClass.getMethod("getObject", Object.class, long.class);
            Method getInt = unsafeClass.getMethod("getInt", Object.class, long.class);
            Method putInt = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
            long sizeOffset = (Long) objectFieldOffset.invoke(unsafe,
                    java.util.ArrayList.class.getDeclaredField("size"));
            long dataOffset = (Long) objectFieldOffset.invoke(unsafe,
                    java.util.ArrayList.class.getDeclaredField("elementData"));
            Object[] data = (Object[]) getObject.invoke(unsafe, list, dataOffset);
            int size = (Integer) getInt.invoke(unsafe, list, sizeOffset);
            if (data == null || size <= 0) {
                return false;
            }
            if (index < 0 || index >= size) {
                index = -1;
                for (int i = 0; i < size; i++) {
                    if (data[i] == element) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    return false;
                }
            }
            System.arraycopy(data, index + 1, data, index, size - index - 1);
            data[size - 1] = null;
            putInt.invoke(unsafe, list, sizeOffset, size - 1);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }
}
