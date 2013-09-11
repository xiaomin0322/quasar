/*
 * Copyright (c) 2013, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.data.record;

import com.google.common.collect.ImmutableSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 *
 * @author pron
 */
public class DynamicRecordType<R> {
    public enum Mode {
        REFLECTION, METHOD_HANDLE, UNSAFE, GENERATION
    };
    private final List<Field<R, ?>> fields;
    int fieldIndex;
    private boolean sealed;
    private Set<Field<? super R, ?>> fieldSet;
    final ClassValue<ClassInfo> vtables;
    private final Mode mode;

    public DynamicRecordType() {
        this(Mode.METHOD_HANDLE);
    }

    public DynamicRecordType(final Mode mode) {
        this.fields = new ArrayList<Field<R, ?>>();
        this.fieldIndex = 0;
        this.mode = mode;
        this.vtables = new ClassValue<ClassInfo>() {
            @Override
            protected ClassInfo computeValue(Class<?> type) {
                seal();
                return new ClassInfo(mode, type, fields);
            }
        };
    }

    static class ClassInfo {
        final Entry[] table;

        private ClassInfo(Mode mode, Class<?> type, Collection<? extends Field<?, ?>> fields) {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();

            try {
                this.table = new Entry[fields.size()];
                for (Field<?, ?> field : fields) {
                    final Method getter = field instanceof Field.ArrayField ? getIndexedGetter(type, field) : getGetter(type, field);
                    final Method setter = field instanceof Field.ArrayField ? getIndexedSetter(type, field) : getSetter(type, field);
                    final java.lang.reflect.Field f = getter == null ? getField(type, field) : null;
                    final boolean indexed = f == null && field instanceof Field.ArrayField;

                    if (f == null && mode == Mode.UNSAFE)
                        throw new RuntimeException("Cannot use UNSAFE mode for class " + type.getName() + " because field " + field.name + " has a getter");

                    if (getter == null && f == null)
                        throw new FieldNotFoundException(field, type);

                    final MethodHandle getterHandle;
                    final MethodHandle setterHandle;

                    if (mode == Mode.METHOD_HANDLE) {
                        getterHandle = fixMethodHandleType(field, f != null ? lookup.unreflectGetter(f) : lookup.unreflect(getter));
                        setterHandle = fixMethodHandleType(field, f != null ? (field instanceof Field.ScalarField ? lookup.unreflectSetter(f) : null) : (setter != null ? lookup.unreflect(setter) : null));
                    } else {
                        getterHandle = null;
                        setterHandle = null;
                    }

                    final long offset;
                    if(mode == Mode.UNSAFE)
                        offset = DynamicUnsafeRecord.getFieldOffset(type, f);
                    else
                        offset = -1L;
                    
                    final DynamicGeneratedRecord.Accessor accessor;
                    if(mode == Mode.GENERATION)
                        accessor = DynamicGeneratedRecord.generateAccessor(type, field);
                    else
                        accessor = null;

                    table[field.id()] = new Entry(f, getter, setter, getterHandle, setterHandle, offset, accessor, indexed);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static java.lang.reflect.Field getField(Class<?> type, Field field) {
        try {
            java.lang.reflect.Field f = type.getDeclaredField(field.name());
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Method getGetter(Class<?> type, Field field) {
        try {
            return type.getMethod("get" + capitalize(field.name()));
        } catch (NoSuchMethodException e) {
        }
        if (field.type() == Field.BOOLEAN) {
            try {
                return type.getMethod("is" + capitalize(field.name()));
            } catch (NoSuchMethodException e) {
            }
        }
        return null;
    }

    private static Method getSetter(Class<?> type, Field field) {
        try {
            return type.getMethod("set" + capitalize(field.name()), field.typeClass());
        } catch (NoSuchMethodException e) {
        }
        return null;
    }

    private static Method getIndexedGetter(Class<?> type, Field field) {
        assert field instanceof Field.ArrayField;
        try {
            return type.getMethod("get" + capitalize(field.name()), int.class);
        } catch (NoSuchMethodException e) {
        }
        if (field.type() == Field.BOOLEAN_ARRAY) {
            try {
                return type.getMethod("is" + capitalize(field.name()), int.class);
            } catch (NoSuchMethodException e) {
            }
        }
        return null;
    }

    private static Method getIndexedSetter(Class<?> type, Field field) {
        assert field instanceof Field.ArrayField;
        try {
            return type.getMethod("set" + capitalize(field.name()), int.class, field.typeClass());
        } catch (NoSuchMethodException e) {
        }
        return null;
    }

    private static MethodHandle fixMethodHandleType(Field field, MethodHandle mh) throws IllegalAccessException {
        if (mh == null)
            return null;

        final MethodType origType = mh.type();
        final Class<?>[] params = origType.parameterArray();

        params[0] = Object.class;
        for (int i = 1; i < params.length; i++) {
            if (!params[i].isPrimitive())
                params[i] = Object.class;
        }

        Class<?> rtype = origType.returnType();
        if(field instanceof Field.ArrayField) {
            assert rtype.isArray();
            if(!rtype.getComponentType().isPrimitive())
                rtype = Object[].class;
        } else {
            if(!rtype.isPrimitive())
                rtype = Object.class;
        }
        
        final MethodType mt = MethodType.methodType(rtype, params);
        return mh.asType(mt);
    }

    private static String capitalize(String str) {
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    public Field.BooleanField<R> booleanField(String name) {
        return addField(new Field.BooleanField<R>(name, -1));
    }

    public Field.ByteField<R> byteField(String name) {
        return addField(new Field.ByteField<R>(name, -1));
    }

    public Field.ShortField<R> shortField(String name) {
        return addField(new Field.ShortField<R>(name, -1));
    }

    public Field.IntField<R> intField(String name) {
        return addField(new Field.IntField<R>(name, -1));
    }

    public Field.LongField<R> longField(String name) {
        return addField(new Field.LongField<R>(name, -1));
    }

    public Field.FloatField<R> floatField(String name) {
        return addField(new Field.FloatField<R>(name, -1));
    }

    public Field.DoubleField<R> doubleField(String name) {
        return addField(new Field.DoubleField<R>(name, -1));
    }

    public Field.CharField<R> charField(String name) {
        return addField(new Field.CharField<R>(name, -1));
    }

    public <V> Field.ObjectField<R, V> objectField(String name, Class<? extends V> type) {
        return addField(new Field.ObjectField<R, V>(name, type, -1));
    }

    public Field.BooleanArrayField<R> booleanArrayField(String name, int length) {
        return addField(new Field.BooleanArrayField<R>(name, length, -1));
    }

    public Field.ByteArrayField<R> byteArrayField(String name, int length) {
        return addField(new Field.ByteArrayField<R>(name, length, -1));
    }

    public Field.ShortArrayField<R> shortArrayField(String name, int length) {
        return addField(new Field.ShortArrayField<R>(name, length, -1));
    }

    public Field.IntArrayField<R> intArrayField(String name, int length) {
        return addField(new Field.IntArrayField<R>(name, length, -1));
    }

    public Field.LongArrayField<R> longArrayField(String name, int length) {
        return addField(new Field.LongArrayField<R>(name, length, -1));
    }

    public Field.FloatArrayField<R> floatArrayField(String name, int length) {
        return addField(new Field.FloatArrayField<R>(name, length, -1));
    }

    public Field.DoubleArrayField<R> doubleArrayField(String name, int length) {
        return addField(new Field.DoubleArrayField<R>(name, length, -1));
    }

    public Field.CharArrayField<R> charArrayField(String name, int length) {
        return addField(new Field.CharArrayField<R>(name, length, -1));
    }

    public <V> Field.ObjectArrayField<R, V> objectArrayField(String name, Class<V> type, int length) {
        return addField(new Field.ObjectArrayField<R, V>(name, type, length, -1));
    }

    private <F extends Field<R, ?>> F addField(F field) {
        if (sealed)
            throw new IllegalStateException("Cannot add fields once a record has been instantiated");

        assert field.id < 0;
        final int id = fieldIndex;
        this.fieldIndex++;

        final Field<R, ?> f;
        switch (field.type()) {
            case Field.BOOLEAN:
                f = Field.booleanField(field.name(), id);
                break;
            case Field.BYTE:
                f = Field.byteField(field.name(), id);
                break;
            case Field.SHORT:
                f = Field.shortField(field.name(), id);
                break;
            case Field.INT:
                f = Field.intField(field.name(), id);
                break;
            case Field.LONG:
                f = Field.longField(field.name(), id);
                break;
            case Field.FLOAT:
                f = Field.floatField(field.name(), id);
                break;
            case Field.DOUBLE:
                f = Field.doubleField(field.name(), id);
                break;
            case Field.CHAR:
                f = Field.charField(field.name(), id);
                break;
            case Field.BOOLEAN_ARRAY:
                f = Field.booleanArrayField(field.name(), ((Field.ArrayField<R, ?>) field).length, id);
                break;
            case Field.BYTE_ARRAY:
                f = Field.byteArrayField(field.name(), ((Field.ArrayField<R, ?>) field).length, id);
                break;
            case Field.SHORT_ARRAY:
                f = Field.shortArrayField(field.name(), ((Field.ArrayField<R, ?>) field).length, id);
                break;
            case Field.INT_ARRAY:
                f = Field.intArrayField(field.name(), ((Field.ArrayField<R, ?>) field).length, id);
                break;
            case Field.LONG_ARRAY:
                f = Field.longArrayField(field.name(), ((Field.ArrayField<R, ?>) field).length, id);
                break;
            case Field.FLOAT_ARRAY:
                f = Field.floatArrayField(field.name(), ((Field.ArrayField<R, ?>) field).length, id);
                break;
            case Field.DOUBLE_ARRAY:
                f = Field.doubleArrayField(field.name(), ((Field.ArrayField<R, ?>) field).length, id);
                break;
            case Field.CHAR_ARRAY:
                f = Field.charArrayField(field.name(), ((Field.ArrayField<R, ?>) field).length, id);
                break;
            case Field.OBJECT:
                f = Field.objectField(field.name(), field.typeClass(), id);
                break;
            case Field.OBJECT_ARRAY:
                f = Field.objectArrayField(field.name(), field.typeClass().getComponentType(), ((Field.ArrayField<R, ?>) field).length, id);
                break;
            default:
                throw new AssertionError();
        }
        fields.add(f);
        return (F) f;
    }

    private void seal() {
        if (!sealed) {
            this.sealed = true;
            this.fieldSet = (Set) ImmutableSet.copyOf(fields);
        }
    }

    Set<Field<? super R, ?>> fieldSet() {
        seal();
        return fieldSet;
    }

    ClassInfo getClassInfo(Class<?> clazz) {
        return vtables.get(clazz);
    }

    public Record<R> newInstance(R target) {
        seal();
        switch (mode) {
            case METHOD_HANDLE:
                return new DynamicMethodHandleRecord<R>(this, target);
            case REFLECTION:
                return new DynamicReflectionRecord<R>(this, target);
            case UNSAFE:
                return new DynamicUnsafeRecord<R>(this, target);
            case GENERATION:
                return new DynamicGeneratedRecord<R>(this, target);
        }
        throw new AssertionError("unreachable");
    }

    @Override
    public String toString() {
        return fields.toString();
    }

    static class Entry {
        final java.lang.reflect.Field field;
        final Method getter;
        final Method setter;
        final MethodHandle getterHandle;
        final MethodHandle setterHandle;
        final long offset;
        final DynamicGeneratedRecord.Accessor accessor;
        final boolean indexed;

        public Entry(java.lang.reflect.Field field, Method getter, Method setter, MethodHandle getterHandle, MethodHandle setterHandle, long offset, DynamicGeneratedRecord.Accessor accessor, boolean indexed) {
            this.field = field;
            this.getter = getter;
            this.setter = setter;
            this.getterHandle = getterHandle;
            this.setterHandle = setterHandle;
            this.offset = offset;
            this.accessor = accessor;
            this.indexed = indexed;
        }
    }
}
