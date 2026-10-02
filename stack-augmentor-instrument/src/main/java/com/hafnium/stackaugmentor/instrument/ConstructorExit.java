package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.instrument.bridge.Dispatch;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.collection.ArrayFactory;
import net.bytebuddy.implementation.bytecode.constant.TextConstant;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * The counterpart of the exit advice for constructors, which ByteBuddy's {@code Advice} cannot catch exceptions in:
 * handlers that pass the id parameters to {@link Dispatch#onThrow} without a receiver, as {@code <init>}, and rethrow
 * the exception. As Java, with the handler written twice:
 * <pre>{@code
 * Order(long orderId) {
 *     try { <computing the arguments> } catch (Throwable t) { Dispatch.onThrow(...); throw t; }
 *     super(<arguments>);
 *     try { <the body> } catch (Throwable t) { Dispatch.onThrow(...); throw t; }
 * }
 * }</pre>
 *
 * <p>The {@code super(...)} or {@code this(...)} call itself is not covered: the verifier accepts no handler around it,
 * because no stack map frame can describe the state where {@code this} is in the middle of being initialized. So
 * exceptions thrown by the called constructor get no ids in this frame. Two handlers are needed because {@code this} has
 * different types before and after that call. A normal return runs no added code.
 */
public final class ConstructorExit {

    private static final TypeDescription.Generic OBJECT = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(Object.class);
    private static final TypeDescription.Generic STRING = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(String.class);
    private static final String THROWABLE = "java/lang/Throwable";

    private ConstructorExit() {
    }

    /** The handler for the constructors that {@code constructors} matches, with the id parameters that {@code parameters} selects. */
    public static AsmVisitorWrapper on(IdParameters parameters, ElementMatcher<? super MethodDescription> constructors) {
        return new AsmVisitorWrapper.ForDeclaredMethods()
                .invokable(constructors, (type, constructor, methodVisitor, context, typePool, writerFlags, readerFlags) ->
                        new Visitor(methodVisitor, type, constructor, parameters.select(type, constructor), context))
                // The handler's stack map frame is written in the expanded form, so all frames must be.
                .readerFlags(ClassReader.EXPAND_FRAMES);
    }

    private static final class Visitor extends MethodVisitor {

        private final TypeDescription type;
        private final MethodDescription constructor;
        private final List<IdParameter> selected;
        private final Implementation.Context context;
        private final Label begin = new Label();
        private final Label beforeInit = new Label();
        private final Label afterInit = new Label();
        private final Label end = new Label();
        /** Objects created with {@code new} whose constructor has not been called yet. */
        private int pendingNew;
        private boolean started;

        Visitor(MethodVisitor methodVisitor, TypeDescription type, MethodDescription constructor, List<IdParameter> selected,
                Implementation.Context context) {
            super(Opcodes.ASM9, methodVisitor);
            this.type = type;
            this.constructor = constructor;
            this.selected = selected;
            this.context = context;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitLabel(begin);
        }

        @Override
        public void visitTypeInsn(int opcode, String typeName) {
            super.visitTypeInsn(opcode, typeName);
            if (opcode == Opcodes.NEW) {
                pendingNew++;
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (started || opcode != Opcodes.INVOKESPECIAL || !name.equals(MethodDescription.CONSTRUCTOR_INTERNAL_NAME)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            } else if (pendingNew > 0) {
                // Initializes an object created with new, e.g. an argument of super(...).
                pendingNew--;
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            } else {
                // The super(...) or this(...) call: before it this is uninitialized, after it initialized.
                started = true;
                super.visitLabel(beforeInit);
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                super.visitLabel(afterInit);
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (!started || selected.isEmpty()) {
                super.visitMaxs(maxStack, maxLocals);
                return;
            }
            super.visitLabel(end);
            Label argumentsHandler = new Label();
            Label bodyHandler = new Label();
            super.visitTryCatchBlock(begin, beforeInit, argumentsHandler, THROWABLE);
            super.visitTryCatchBlock(afterInit, end, bodyHandler, THROWABLE);
            int handlerStack = handler(argumentsHandler, Opcodes.UNINITIALIZED_THIS);
            handler(bodyHandler, type.getInternalName());
            super.visitMaxs(Math.max(maxStack, handlerStack), maxLocals);
        }

        /**
         * Writes a handler: {@code Dispatch.onThrow(null, thrown, owner, "<init>", values, labels)}, then rethrows. Its
         * frame names {@code this} as {@code thisType} and the parameters with their declared types. Returns its maximal
         * stack size.
         */
        private int handler(Label handler, Object thisType) {
            super.visitLabel(handler);
            if (context.getClassFileVersion().isAtLeast(ClassFileVersion.JAVA_V6)) {
                List<Object> locals = new ArrayList<>();
                locals.add(thisType);
                for (ParameterDescription parameter : constructor.getParameters()) {
                    locals.add(frameType(parameter.getType().asErasure()));
                }
                super.visitFrame(Opcodes.F_NEW, locals.size(), locals.toArray(), 1, new Object[]{THROWABLE});
            }
            // Stack: thrown, thrown, null -> thrown, null, thrown.
            super.visitInsn(Opcodes.DUP);
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitInsn(Opcodes.SWAP);
            StackManipulation.Size size = arguments().apply(mv, context);
            super.visitInsn(Opcodes.ATHROW);
            return 3 + size.getMaximalSize();
        }

        /** Pushes the owner, the method name, the id values and their labels, and calls {@link Dispatch#onThrow}. */
        private StackManipulation arguments() {
            List<StackManipulation> values = new ArrayList<>(selected.size());
            List<StackManipulation> labels = new ArrayList<>(selected.size());
            for (IdParameter idParameter : selected) {
                TypeDescription.Generic parameterType = idParameter.parameter().getType();
                values.add(new StackManipulation.Compound(
                        MethodVariableAccess.of(parameterType).loadFrom(idParameter.parameter().getOffset()),
                        Assigner.DEFAULT.assign(parameterType, OBJECT, Assigner.Typing.DYNAMIC)));
                labels.add(new TextConstant(idParameter.encodedLabel()));
            }
            return new StackManipulation.Compound(
                    new TextConstant(type.getName()),
                    new TextConstant(MethodDescription.CONSTRUCTOR_INTERNAL_NAME),
                    ArrayFactory.forType(OBJECT).withValues(values),
                    ArrayFactory.forType(STRING).withValues(labels),
                    MethodInvocation.invoke(onThrow()));
        }

        private static MethodDescription.InDefinedShape onThrow() {
            try {
                return new MethodDescription.ForLoadedMethod(Dispatch.class.getMethod("onThrow",
                        Object.class, Throwable.class, String.class, String.class, Object[].class, String[].class));
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }

        /** A local variable's type as a stack map frame names it. */
        private static Object frameType(TypeDefinition type) {
            if (type.represents(long.class)) {
                return Opcodes.LONG;
            } else if (type.represents(double.class)) {
                return Opcodes.DOUBLE;
            } else if (type.represents(float.class)) {
                return Opcodes.FLOAT;
            } else if (type.isPrimitive()) {
                return Opcodes.INTEGER;
            }
            return type.asErasure().getInternalName();
        }
    }
}
