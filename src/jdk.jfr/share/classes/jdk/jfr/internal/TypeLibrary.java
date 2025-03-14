/*
 * Copyright (c) 2016, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.jfr.internal;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import jdk.jfr.AnnotationElement;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.MetadataDefinition;
import jdk.jfr.Name;
import jdk.jfr.SettingControl;
import jdk.jfr.SettingDescriptor;
import jdk.jfr.Timespan;
import jdk.jfr.Timestamp;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.internal.util.Utils;
import jdk.jfr.internal.util.ImplicitFields;
import jdk.internal.module.Modules;

public final class TypeLibrary {
    private static boolean implicitFieldTypes;
    private static final Map<Long, Type> types = LinkedHashMap.newLinkedHashMap(350);
    private static volatile boolean initialized;
    static final ValueDescriptor DURATION_FIELD = createDurationField();
    static final ValueDescriptor THREAD_FIELD = createThreadField();
    static final ValueDescriptor STACK_TRACE_FIELD = createStackTraceField();
    static final ValueDescriptor START_TIME_FIELD = createStartTimeField();

    private TypeLibrary() {
        throw new InternalError("Don't instantiate");
    }

    private static ValueDescriptor createStartTimeField() {
        var annos = createStandardAnnotations("Start Time", null);
        annos.add(new jdk.jfr.AnnotationElement(Timestamp.class, Timestamp.TICKS));
        return PrivateAccess.getInstance().newValueDescriptor(ImplicitFields.START_TIME, Type.LONG, annos, 0, false,
                ImplicitFields.START_TIME);
    }

    private static ValueDescriptor createStackTraceField() {
        var annos = createStandardAnnotations("Stack Trace", "Stack Trace starting from the method the event was committed in");
        return PrivateAccess.getInstance().newValueDescriptor(ImplicitFields.STACK_TRACE, Type.STACK_TRACE, annos, 0, true,
                ImplicitFields.STACK_TRACE);
    }

    private static ValueDescriptor createThreadField() {
        var annos = createStandardAnnotations("Event Thread", "Thread in which event was committed in");
        return PrivateAccess.getInstance().newValueDescriptor(ImplicitFields.EVENT_THREAD, Type.THREAD, annos, 0, true,
                ImplicitFields.EVENT_THREAD);
    }

    private static ValueDescriptor createDurationField() {
        var annos = createStandardAnnotations("Duration", null);
        annos.add(new jdk.jfr.AnnotationElement(Timespan.class, Timespan.TICKS));
        return PrivateAccess.getInstance().newValueDescriptor(ImplicitFields.DURATION, Type.LONG, annos, 0, false, ImplicitFields.DURATION);
    }

    public static synchronized void initialize() {
        // The usual case is that TypeLibrary is initialized only once by the MetadataRepository singleton.
        // However, this check is needed to ensure some tools (ie. GraalVM Native Image) do not perform the initialization routine twice.
        if (initialized) {
            return;
        }
        List<Type> jvmTypes;
        try {
            jvmTypes = MetadataLoader.createTypes();
            jvmTypes.sort(Comparator.comparingLong(Type::getId));
        } catch (IOException e) {
            throw new Error("JFR: Could not read metadata");
        }
        visitReachable(jvmTypes, t -> !types.containsKey(t.getId()), t -> types.put(t.getId(), t));
        initialized = true;
        if (Logger.shouldLog(LogTag.JFR_SYSTEM_METADATA, LogLevel.INFO)) {
            Stream<Type> s = types.values().stream().sorted((x, y) -> Long.compare(x.getId(), y.getId()));
            s.forEach(t -> t.log("Added", LogTag.JFR_SYSTEM_METADATA, LogLevel.INFO));
        }
    }

    public static synchronized Collection<Type> getTypes() {
        return new ArrayList<>(types.values());
    }

    // Returned list should be mutable (for in-place sorting)
    public static synchronized List<Type> getVisibleTypes() {
        List<Type> visible = new ArrayList<>(types.size());
        types.values().forEach(t -> {
            if (t.isVisible()) {
                visible.add(t);
            }
        });
        return visible;
    }

    public static synchronized Type createAnnotationType(Class<? extends Annotation> a) {
        if (shouldPersist(a)) {
            Type type = defineType(a, Type.SUPER_TYPE_ANNOTATION, false);
            if (type != null) {
                SecuritySupport.makeVisibleToJFR(a);
                for (Method method : a.getDeclaredMethods()) {
                    type.add(PrivateAccess.getInstance().newValueDescriptor(method.getReturnType(), method.getName()));
                }
                ArrayList<AnnotationElement> aes = new ArrayList<>();
                for (Annotation annotation : resolveRepeatedAnnotations(a.getAnnotations())) {
                    AnnotationElement ae = createAnnotation(annotation);
                    if (ae != null) {
                        aes.add(ae);
                    }
                }
                type.setAnnotations(aes);
            }
            return getType(a);
        }
        return null;
    }

    public static synchronized AnnotationElement createAnnotation(Annotation annotation) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        Type type = createAnnotationType(annotationType);
        if (type != null) {
            List<Object> values = new ArrayList<>();
            for (ValueDescriptor v : type.getFields()) {
                values.add(invokeAnnotation(annotation, v.getName()));
            }
            // Only annotation classes in the boot class loader can always be resolved.
            boolean bootClassLoader = annotationType.getClassLoader() == null;
            return PrivateAccess.getInstance().newAnnotation(type, values, bootClassLoader);
        }
        return null;
    }

    private static Object invokeAnnotation(Annotation annotation, String methodName) {
        final Method m;
        try {
            m = annotation.getClass().getMethod(methodName, new Class<?>[0]);
        } catch (NoSuchMethodException e1) {
            throw (Error) new InternalError("Could not locate method " + methodName + " in annotation " + annotation.getClass().getName());
        }
        // Add export from JDK proxy module
        if (annotation.getClass().getClassLoader() == null) {
            if (annotation.getClass().getName().contains("Proxy")) {
                Module proxyModule = annotation.getClass().getModule();
                String proxyPackage = annotation.getClass().getPackageName();
                Module jfrModule = TypeLibrary.class.getModule();
                Modules.addExports(proxyModule, proxyPackage, jfrModule);
            }
        }
        m.setAccessible(true);
        try {
            return m.invoke(annotation, new Object[0]);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw (Error) new InternalError("Could not get value for method " + methodName + " in annotation " + annotation.getClass().getName());
        }
    }

    private static boolean shouldPersist(Class<? extends Annotation> a) {
        if (a == MetadataDefinition.class || a.getAnnotation(MetadataDefinition.class) == null) {
            return false;
        }
        return true;
    }

    private static boolean isDefined(Class<?> clazz) {
        return types.containsKey(Type.getTypeId(clazz));
    }

    private static Type getType(Class<?> clazz) {
        return types.get(Type.getTypeId(clazz));
    }

    private static Type defineType(Class<?> clazz, String superType, boolean eventType) {
        if (!isDefined(clazz)) {
            Name name = clazz.getAnnotation(Name.class);
            String typeName = clazz.getName();
            if (name != null) {
                typeName = Utils.validTypeName(name.value(), typeName);
            }
            long id = Type.getTypeId(clazz);
            Type t;
            if (eventType) {
                t = new PlatformEventType(typeName, id, Utils.isJDKClass(clazz), true);
            } else {
                t = new Type(typeName, superType, id);
            }
            types.put(t.getId(), t);
            return t;
        }
        return null;
    }

    public static synchronized Type createType(Class<?> clazz) {
        return createType(clazz, Collections.emptyList(), Collections.emptyList());
    }

    public static synchronized Type createType(Class<?> clazz, List<AnnotationElement> dynamicAnnotations, List<ValueDescriptor> dynamicFields) {

        if (Thread.class == clazz) {
            return Type.THREAD;
        }

        if (Class.class.isAssignableFrom(clazz)) {
            return Type.CLASS;
        }

        if (String.class.equals(clazz)) {
            return Type.STRING;
        }

        if (isDefined(clazz)) {
            return getType(clazz);
        }

        if (clazz.isPrimitive()) {
            return defineType(clazz, null,false);
        }

        if (clazz.isArray()) {
            throw new InternalError("Arrays not supported");
        }

        // STRUCT
        String superType = null;
        boolean eventType = false;
        if (isEventClass(clazz)) {
            superType = Type.SUPER_TYPE_EVENT;
            eventType= true;
        }
        if (SettingControl.class.isAssignableFrom(clazz)) {
            superType = Type.SUPER_TYPE_SETTING;
        }

        // forward declare to avoid infinite recursion
        defineType(clazz, superType, eventType);
        Type type = getType(clazz);

        if (eventType) {
            ImplicitFields ifs = new ImplicitFields(clazz);
            addImplicitFields(type, true, ifs.hasDuration(), ifs.hasEventThread(), ifs.hasStackTrace(), false);
            addUserFields(clazz, type, dynamicFields);
            type.trimFields();
        }
        addAnnotations(clazz, type, dynamicAnnotations);

        if (Utils.isJDKClass(clazz)) {
            type.log("Added", LogTag.JFR_SYSTEM_METADATA, LogLevel.INFO);
        } else {
            type.log("Added", LogTag.JFR_METADATA, LogLevel.INFO);
        }
        return type;
    }

    private static boolean isEventClass(Class<?> clazz) {
        if (jdk.internal.event.Event.class.isAssignableFrom(clazz)) {
            return true;
        }
        if (MirrorEvent.class.isAssignableFrom(clazz)) {
            return true;
        }
        return false;
    }

    private static void addAnnotations(Class<?> clazz, Type type, List<AnnotationElement> dynamicAnnotations) {
        ArrayList<AnnotationElement> aes = new ArrayList<>();
        if (dynamicAnnotations.isEmpty()) {
            for (Annotation a : Utils.getAnnotations(clazz)) {
                AnnotationElement ae = createAnnotation(a);
                if (ae != null) {
                    aes.add(ae);
                }
            }
        } else {
            List<Type> newTypes = new ArrayList<>();
            aes.addAll(dynamicAnnotations);
            for (AnnotationElement ae : dynamicAnnotations) {
                newTypes.add(PrivateAccess.getInstance().getType(ae));
            }
            addTypes(newTypes);
        }
        type.setAnnotations(aes);
        aes.trimToSize();
    }

    private static void addUserFields(Class<?> clazz, Type type, List<ValueDescriptor> dynamicFields) {
        Map<String, ValueDescriptor> dynamicFieldSet = HashMap.newHashMap(dynamicFields.size());
        for (ValueDescriptor dynamicField : dynamicFields) {
            dynamicFieldSet.put(dynamicField.getName(), dynamicField);
        }
        List<Type> newTypes = new ArrayList<>();
        for (Field field : Utils.getVisibleEventFields(clazz)) {
            ValueDescriptor vd = dynamicFieldSet.get(field.getName());
            if (vd != null) {
                if (!vd.getTypeName().equals(field.getType().getName())) {
                    throw new InternalError("Type expected to match for field " + vd.getName() + " expected "  + field.getType().getName() + " but got " + vd.getTypeName());
                }
                for (AnnotationElement ae : vd.getAnnotationElements()) {
                    newTypes.add(PrivateAccess.getInstance().getType(ae));
                }
                newTypes.add(PrivateAccess.getInstance().getType(vd));
            } else {
                vd = createField(field);
            }
            if (vd != null) {
                type.add(vd);
            }
        }
        addTypes(newTypes);
    }

    // By convention all events have these fields.
    public static synchronized void addImplicitFields(Type type, boolean requestable, boolean hasDuration, boolean hasThread, boolean hasStackTrace, boolean hasCutoff) {
        if (!implicitFieldTypes) {
            createAnnotationType(Timespan.class);
            createAnnotationType(Timestamp.class);
            createAnnotationType(Label.class);
            implicitFieldTypes = true;
        }
        addFields(type, requestable, hasDuration, hasThread, hasStackTrace, hasCutoff);
    }

    private static void addFields(Type type, boolean requestable, boolean hasDuration, boolean hasThread, boolean hasStackTrace, boolean hasCutoff) {
        type.add(START_TIME_FIELD);
        if (hasDuration || hasCutoff) {
            type.add(DURATION_FIELD);
        }
        if (hasThread) {
            type.add(THREAD_FIELD);
        }
        if (hasStackTrace) {
            type.add(STACK_TRACE_FIELD);
        }
    }

    private static List<AnnotationElement> createStandardAnnotations(String name, String description) {
        List<AnnotationElement> annotationElements = new ArrayList<>(2);
        annotationElements.add(new jdk.jfr.AnnotationElement(Label.class, name));
        if (description != null) {
            annotationElements.add(new jdk.jfr.AnnotationElement(Description.class, description));
        }
        return annotationElements;
    }

    private static ValueDescriptor createField(Field field) {
        int mod = field.getModifiers();
        if (Modifier.isTransient(mod)) {
            return null;
        }
        if (Modifier.isStatic(mod)) {
            return null;
        }
        Class<?> fieldType = field.getType();
        if (!Type.isKnownType(fieldType)) {
            return null;
        }
        boolean constantPool = Thread.class == fieldType || fieldType == Class.class;
        Type type = createType(fieldType);
        String fieldName = field.getName();
        Name name = field.getAnnotation(Name.class);
        String useName = fieldName;
        if (name != null) {
            useName = Utils.validJavaIdentifier(name.value(), useName);
        }
        List<jdk.jfr.AnnotationElement> ans = new ArrayList<>();
        for (Annotation a : resolveRepeatedAnnotations(field.getAnnotations())) {
            AnnotationElement ae = createAnnotation(a);
            if (ae != null) {
                ans.add(ae);
            }
        }
        return PrivateAccess.getInstance().newValueDescriptor(useName, type, ans, 0, constantPool, fieldName);
    }

    private static List<Annotation> resolveRepeatedAnnotations(Annotation[] annotations) {
        List<Annotation> annos = new ArrayList<>(annotations.length);
        for (Annotation a : annotations) {
            boolean repeated = false;
            Method m;
            try {
                m = a.annotationType().getMethod("value");
                Class<?> returnType = m.getReturnType();
                if (returnType.isArray()) {
                    Class<?> ct = returnType.getComponentType();
                    if (Annotation.class.isAssignableFrom(ct) && ct.getAnnotation(Repeatable.class) != null) {
                        Object res = m.invoke(a, new Object[0]);
                        if (res instanceof Annotation[] anns) {
                            for (Annotation rep : anns) {
                                annos.add(rep);
                            }
                            repeated = true;
                        }
                    }
                }
            } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                // Ignore, can't access repeatable information
            }
            if (!repeated) {
                annos.add(a);
            }
        }
        return annos;
    }

    // Purpose of this method is to mark types that are reachable
    // from registered event types. Those types that are not reachable can
    // safely be removed
    // Returns true if type was removed
    public static synchronized boolean clearUnregistered() {
        Logger.log(LogTag.JFR_METADATA, LogLevel.TRACE, "Cleaning out obsolete metadata");
        List<Type> registered = new ArrayList<>();
        for (Type type : types.values()) {
            if (type instanceof PlatformEventType pType) {
                if (pType.isRegistered()) {
                    registered.add(type);
                }
            }
        }
        visitReachable(registered, t -> t.getRemove(), t -> t.setRemove(false));
        List<Long> removeIds = new ArrayList<>();
        for (Type type :  types.values()) {
            if (type.getRemove() && !Type.isDefinedByJVM(type.getId())) {
                removeIds.add(type.getId());
                if (Logger.shouldLog(LogTag.JFR_METADATA, LogLevel.TRACE)) {
                    Logger.log(LogTag.JFR_METADATA, LogLevel.TRACE, "Removed obsolete metadata " + type.getName());
                }
            }
            // Optimization, set to true now to avoid iterating
            // types first thing at next call to clearUnregistered
            type.setRemove(true);
        }
        for (Long id : removeIds) {
            types.remove(id);
        }
        return !removeIds.isEmpty();
    }

    public static synchronized void addType(Type type) {
        addTypes(Collections.singletonList(type));
    }

    public static synchronized void addTypes(List<Type> ts) {
        if (!ts.isEmpty()) {
            visitReachable(ts, t -> !types.containsKey(t.getId()), t -> types.put(t.getId(), t));
        }
    }

    /**
     * Iterates all reachable types from a start collection
     *
     * @param rootSet the types to start from
     * @param p if a type should be accepted
     * @param c action to take on an accepted type
     */
    private  static void visitReachable(Collection<Type> rootSet, Predicate<Type> p,  Consumer<Type> c) {
        Queue<Type> typeQ = new ArrayDeque<>(rootSet);
        while (!typeQ.isEmpty()) {
            Type type = typeQ.poll();
            if (p.test(type)) {
                c.accept(type);
                visitAnnotations(typeQ, type.getAnnotationElements());
                for (ValueDescriptor v : type.getFields()) {
                    typeQ.add(PrivateAccess.getInstance().getType(v));
                    visitAnnotations(typeQ, v.getAnnotationElements());
                }
                if (type instanceof PlatformEventType pe) {
                    for (SettingDescriptor s : pe.getAllSettings()) {
                        typeQ.add(PrivateAccess.getInstance().getType(s));
                        visitAnnotations(typeQ, s.getAnnotationElements());
                    }
                }
            }
        }
    }

    private static void visitAnnotations(Queue<Type> typeQ, List<AnnotationElement> aes) {
        Queue<AnnotationElement> aQ = new ArrayDeque<>(aes);
        Set<AnnotationElement> visited = new HashSet<>();
        while (!aQ.isEmpty()) {
            AnnotationElement ae = aQ.poll();
            if (!visited.contains(ae)) {
                Type ty = PrivateAccess.getInstance().getType(ae);
                typeQ.add(ty);
                visited.add(ae);
            }
            aQ.addAll(ae.getAnnotationElements());
        }
    }

    public static synchronized void removeType(long id) {
        types.remove(id);
    }
}
