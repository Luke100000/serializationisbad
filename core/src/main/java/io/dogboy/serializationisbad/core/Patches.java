package io.dogboy.serializationisbad.core;

import io.dogboy.serializationisbad.core.config.PatchModule;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class Patches {

    public static PatchModule getPatchModuleForClass(String className) {
        for (PatchModule patchModule : SerializationIsBad.getInstance().getConfig().getPatchModules()) {
            if (patchModule.getClassesToPatch().contains(className)) {
                return patchModule;
            }
        }

        return null;
    }

    public static ClassNode readClassNode(byte[] classBytecode) {
        ClassNode classNode = new ClassNode();
        ClassReader classReader = new ClassReader(classBytecode);
        classReader.accept(classNode, 0);
        return classNode;
    }

    public static byte[] writeClassNode(ClassNode classNode) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    public static void applyPatches(String className, ClassNode classNode) {
        for (MethodNode methodNode : classNode.methods) {
            InsnList instructions = methodNode.instructions;
            for (int i = 0; i < instructions.size(); i++) {
                AbstractInsnNode instruction = instructions.get(i);
                if (instruction.getOpcode() == Opcodes.NEW
                        && instruction instanceof TypeInsnNode && "java/io/ObjectInputStream".equals(((TypeInsnNode) instruction).desc)) {
                    ((TypeInsnNode) instruction).desc = "io/dogboy/serializationisbad/core/ClassFilteringObjectInputStream";

                    SerializationIsBad.logger.info("  (1/2) Redirecting ObjectInputStream to ClassFilteringObjectInputStream in method " + methodNode.name);
                } else if (instruction.getOpcode() == Opcodes.INVOKESPECIAL
                        && instruction instanceof MethodInsnNode && "java/io/ObjectInputStream".equals(((MethodInsnNode) instruction).owner)
                        && "<init>".equals(((MethodInsnNode) instruction).name)) {
                    ((MethodInsnNode) instruction).owner = "io/dogboy/serializationisbad/core/ClassFilteringObjectInputStream";
                    ((MethodInsnNode) instruction).desc = "(Ljava/io/InputStream;Lio/dogboy/serializationisbad/core/config/PatchModule;)V";

                    InsnList additionalInstructions = new InsnList();
                    additionalInstructions.add(new LdcInsnNode(className));
                    additionalInstructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "io/dogboy/serializationisbad/core/Patches",
                            "getPatchModuleForClass", "(Ljava/lang/String;)Lio/dogboy/serializationisbad/core/config/PatchModule;", false));

                    instructions.insertBefore(instruction, additionalInstructions);

                    SerializationIsBad.logger.info("  (2/2) Redirecting ObjectInputStream to ClassFilteringObjectInputStream in method " + methodNode.name);
                } else if (instruction.getOpcode() == Opcodes.INVOKESTATIC
                        && instruction instanceof MethodInsnNode
                        && "org/apache/commons/lang3/SerializationUtils".equals(((MethodInsnNode) instruction).owner)
                        && "deserialize".equals(((MethodInsnNode) instruction).name)) {

                    ((MethodInsnNode) instruction).owner = "io/dogboy/serializationisbad/core/Patches";

                    if ("(Ljava/io/InputStream;)Ljava/lang/Object;".equals(((MethodInsnNode) instruction).desc)) {
                        ((MethodInsnNode) instruction).desc = "(Ljava/io/InputStream;Lio/dogboy/serializationisbad/core/config/PatchModule;)Ljava/lang/Object;";
                    } else if ("([B)Ljava/lang/Object;".equals(((MethodInsnNode) instruction).desc)) {
                        ((MethodInsnNode) instruction).desc = "([BLio/dogboy/serializationisbad/core/config/PatchModule;)Ljava/lang/Object;";
                    } else {
                        throw new RuntimeException("Unknown desc for SerializationUtils.deserialize: " + ((MethodInsnNode) instruction).desc);
                    }

                    InsnList additionalInstructions = new InsnList();
                    additionalInstructions.add(new LdcInsnNode(className));
                    additionalInstructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "io/dogboy/serializationisbad/core/Patches",
                            "getPatchModuleForClass", "(Ljava/lang/String;)Lio/dogboy/serializationisbad/core/config/PatchModule;", false));

                    instructions.insertBefore(instruction, additionalInstructions);

                    SerializationIsBad.logger.info("  Redirecting SerializationUtils.deserialize to Patches in method " + methodNode.name);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T deserialize(InputStream inputStream, PatchModule patchModule) {
        try (ClassFilteringObjectInputStream objectInputStream = new ClassFilteringObjectInputStream(inputStream, patchModule)) {
            return (T) objectInputStream.readObject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T deserialize(byte[] objectData, PatchModule patchModule) {
        return Patches.deserialize(new ByteArrayInputStream(objectData), patchModule);
    }

}
