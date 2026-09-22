package me.NedayAzady.profile;

import org.bukkit.Bukkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defensive, fully-reflective access to Minecraft server internals
 * (NMS/CraftBukkit/authlib) so the addon never needs hard NMS classes at
 * compile time and degrades gracefully on unsupported versions.
 *
 * <p>All lookups are structural (by declaring-type simple name) instead of by
 * obfuscated field name, which keeps the same code working across Spigot
 * 1.8.x through 1.20.x where NMS field names differ between mappings.
 */
public final class NmsReflection {

    private NmsReflection() {
    }

    private static final Map<String, Object> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();

    /**
     * Examples: "org.bukkit.craftbukkit.v1_16_R3.CraftServer" -> "v1_16_R3".
     */
    public static String craftBukkitSuffix() {
        try {
            String pkg = Bukkit.getServer().getClass().getPackage().getName();
            int idx = pkg.lastIndexOf('.');
            return idx >= 0 ? pkg.substring(idx + 1) : pkg;
        } catch (Throwable t) {
            return "v1_16_R3";
        }
    }

    /**
     * Finds a class by simple name across the NMS/CraftBukkit packages that
     * changed location over the years, then the authlib package, then any
     * extra candidate packages. Returns null if not found.
     */
    public static Class<?> findClass(String simpleName, String... extraPackages) {
        List<String> candidates = new ArrayList<>();
        String suffix = craftBukkitSuffix();
        candidates.add("net.minecraft.server." + suffix + "." + simpleName);
        candidates.add("org.bukkit.craftbukkit." + suffix + "." + simpleName);
        candidates.add("net.minecraft.network.protocol.game." + simpleName);
        candidates.add("net.minecraft.network." + simpleName);
        candidates.add("com.mojang.authlib." + simpleName);
        candidates.add("com.mojang.authlib.properties." + simpleName);
        if (extraPackages != null) {
            for (String pkg : extraPackages) {
                candidates.add(pkg + "." + simpleName);
            }
        }
        for (String fqn : candidates) {
            Class<?> cls = findClassExact(fqn);
            if (cls != null) {
                return cls;
            }
        }
        return null;
    }

    public static Class<?> findClassExact(String fqn) {
        Object cached = CLASS_CACHE.get(fqn);
        if (cached != null) {
            return cached == MISSING ? null : (Class<?>) cached;
        }
        try {
            Class<?> cls = Class.forName(fqn);
            CLASS_CACHE.put(fqn, cls);
            return cls;
        } catch (Throwable t) {
            CLASS_CACHE.put(fqn, MISSING);
            return null;
        }
    }

    public static String simpleName(Object obj) {
        if (obj == null) {
            return "";
        }
        String name = obj.getClass().getName();
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    public static boolean isSimpleName(Object obj, String simple) {
        return simpleName(obj).equals(simple);
    }

    /**
     * Returns the first non-null declared field (searching up the hierarchy)
     * whose value is assignable from the given type's simple names.
     */
    public static Object readField(Object target, String... acceptedTypeSimpleNames) {
        Field field = findField(target, acceptedTypeSimpleNames);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Returns all non-null values of declared fields whose type matches.
     */
    public static List<Object> readAllFields(Object target, String... acceptedTypeSimpleNames) {
        List<Object> values = new ArrayList<>();
        List<Field> fields = fieldsOfType(target, acceptedTypeSimpleNames);
        for (Field field : fields) {
            try {
                Object value = field.get(target);
                if (value != null) {
                    values.add(value);
                }
            } catch (Throwable ignored) {
            }
        }
        return values;
    }

    public static Field findField(Object target, String... acceptedTypeSimpleNames) {
        if (target == null) {
            return null;
        }
        Class<?> cls = target.getClass();
        List<String> accepted = Arrays.asList(acceptedTypeSimpleNames);
        while (cls != null && cls != Object.class) {
            for (Field field : cls.getDeclaredFields()) {
                String typeName = field.getType().getSimpleName();
                if (accepted.contains(typeName)) {
                    field.setAccessible(true);
                    return field;
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    public static List<Field> fieldsOfType(Object target, String... acceptedTypeSimpleNames) {
        List<Field> result = new ArrayList<>();
        if (target == null) {
            return result;
        }
        List<String> accepted = Arrays.asList(acceptedTypeSimpleNames);
        Class<?> cls = target.getClass();
        while (cls != null && cls != Object.class) {
            for (Field field : cls.getDeclaredFields()) {
                String typeName = field.getType().getSimpleName();
                if (accepted.contains(typeName)) {
                    try {
                        field.setAccessible(true);
                        result.add(field);
                    } catch (Throwable ignored) {
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return result;
    }

    /**
     * Writes a value into a field, accepting private/final fields via
     * setAccessible (works on modern HotSpot for instance final fields).
     */
    public static boolean setField(Object target, Field field, Object value) {
        if (target == null || field == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Replaces a value in a Map or Collection container (by reference equality
     * of the old element).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean replaceInContainer(Object container, Object oldEntry, Object newEntry) {
        if (container == null) {
            return false;
        }
        if (container instanceof Map) {
            Map map = (Map) container;
            for (Object key : map.keySet()) {
                if (map.get(key) == oldEntry) {
                    map.put(key, newEntry);
                    return true;
                }
            }
        } else if (container instanceof List) {
            List list = (List) container;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) == oldEntry) {
                    list.set(i, newEntry);
                    return true;
                }
            }
        } else if (container instanceof java.util.Collection) {
            java.util.Collection coll = (java.util.Collection) container;
            if (coll.remove(oldEntry)) {
                coll.add(newEntry);
                return true;
            }
        }
        return false;
    }

    public static Object call(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        Class<?> cls = target.getClass();
        while (cls != null && cls != Object.class) {
            for (Method method : cls.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (method.getParameterCount() != (args == null ? 0 : args.length)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Like {@link #call} but returns whether a matching method was found AND
     * invoked successfully, so callers can fall back to an alternate spelling.
     */
    public static boolean invokeIfPresent(Object target, String methodName, Object... args) {
        if (target == null) {
            return false;
        }
        Class<?> cls = target.getClass();
        while (cls != null && cls != Object.class) {
            for (Method method : cls.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (method.getParameterCount() != (args == null ? 0 : args.length)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(target, args);
                    return true;
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    public static <T> T callAndCast(Object target, String methodName, Object... args) {
        try {
            return (T) call(target, methodName, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Creates a fresh com.mojang.authlib.GameProfile with ONLY the given
     * textures property (strict per-player isolation; nothing is ever reused).
     */
    public static Object buildIsolatedGameProfile(UUID uuid, String name, String textureValue, String textureSignature) {
        Object gameProfile = newInstance("GameProfile", new Object[]{uuid, name});
        if (gameProfile == null) {
            return null;
        }
        Object properties = call(gameProfile, "getProperties");
        if (properties == null) {
            return null;
        }
        Object property = newInstance("Property", new Object[]{"textures", textureValue, textureSignature});
        if (property == null) {
            return null;
        }
        call(properties, "clear");
        call(properties, "put", "textures", property);
        return gameProfile;
    }

    /**
     * Reflectively calls the clean "getProperties" on a GameProfile and returns
     * the "textures" collection (empty if missing).
     */
    public static List<Object> readTextures(Object gameProfile) {
        List<Object> result = new ArrayList<>();
        if (gameProfile == null) {
            return result;
        }
        Object properties = call(gameProfile, "getProperties");
        if (properties == null) {
            return result;
        }
        Object textures = call(properties, "get", "textures");
        if (textures instanceof java.util.Collection) {
            result.addAll((java.util.Collection) textures);
        }
        return result;
    }

    public static String readTextureValue(Object gameProfile) {
        List<Object> textures = readTextures(gameProfile);
        if (textures.isEmpty()) {
            return null;
        }
        Object property = textures.get(0);
        return (String) call(property, "getValue");
    }

    public static UUID readProfileId(Object gameProfile) {
        if (gameProfile == null) {
            return null;
        }
        return (UUID) call(gameProfile, "getId");
    }

    public static String readProfileName(Object gameProfile) {
        if (gameProfile == null) {
            return null;
        }
        return (String) call(gameProfile, "getName");
    }

    public static boolean hasTextures(Object gameProfile) {
        if (gameProfile == null) {
            return false;
        }
        List<Object> textures = readTextures(gameProfile);
        for (Object property : textures) {
            Object value = call(property, "getValue");
            if (value != null && !((String) value).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static Object newInstance(String simpleName, Object[] args) {
        Class<?> cls = findClass(simpleName);
        if (cls == null) {
            return null;
        }
        return newInstance(cls, args);
    }

    public static Object newInstance(Class<?> cls, Object[] args) {
        if (cls == null) {
            return null;
        }
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            if (ctor.getParameterCount() != (args == null ? 0 : args.length)) {
                continue;
            }
            Object result = newInstance(ctor, args);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Instantiates via a specific constructor, coercing each argument to the
     * matching parameter type (numeric widening, boxed ints, Integer->enum-by-
     * ordinal such as 0->SURVIVAL, nulls for reference types).
     */
    public static Object newInstance(Constructor<?> ctor, Object[] args) {
        if (ctor == null) {
            return null;
        }
        Object[] coerced = coerceArgs(ctor.getParameterTypes(), args);
        if (coerced == null) {
            return null;
        }
        try {
            ctor.setAccessible(true);
            return ctor.newInstance(coerced);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Attempts to coerce each argument to the matching constructor parameter
     * type: numeric widening, boxed/unboxed ints, Integer->enum-by-ordinal
     * (e.g. 0 -> SURVIVAL for gamemode enums) and nulls for reference types.
     * Returns null if any coercion is impossible.
     */
    private static Object[] coerceArgs(Class<?>[] types, Object[] args) {
        Object[] coerced = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object arg = args[i];
            if (arg == null) {
                if (type.isPrimitive()) {
                    return null;
                }
                coerced[i] = null;
                continue;
            }
            if (box(type).isAssignableFrom(arg.getClass())) {
                coerced[i] = arg;
                continue;
            }
            if ((type == int.class || type == Integer.class) && arg instanceof Number) {
                coerced[i] = ((Number) arg).intValue();
                continue;
            }
            if (type == long.class || type == Long.class) {
                if (arg instanceof Number) {
                    coerced[i] = ((Number) arg).longValue();
                    continue;
                }
                return null;
            }
            if (type.isEnum() && arg instanceof Number) {
                Object[] constants = type.getEnumConstants();
                int ordinal = ((Number) arg).intValue();
                if (ordinal >= 0 && ordinal < constants.length) {
                    coerced[i] = constants[ordinal];
                    continue;
                }
                return null;
            }
            if (type == String.class) {
                coerced[i] = arg.toString();
                continue;
            }
            return null;
        }
        return coerced;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    public static Throwable rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    public static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        Throwable root = rootCause(t);
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}