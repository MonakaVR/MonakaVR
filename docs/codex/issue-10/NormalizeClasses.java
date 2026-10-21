import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.objectweb.asm.*;

public class NormalizeClasses {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        boolean apiOnly = args.length > 2 && args[2].equals("--api");
        List<String> output = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                ClassWriter writer = new ClassWriter(0);
                ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (apiOnly) return null;
                        if (descriptor.equals("Lkotlin/jvm/internal/SourceDebugExtension;")) return null;
                        AnnotationVisitor delegate = super.visitAnnotation(descriptor, visible);
                        if (descriptor.equals("Lkotlin/coroutines/jvm/internal/DebugMetadata;")) {
                            return new AnnotationVisitor(Opcodes.ASM9, delegate) {
                                @Override public void visit(String name, Object value) {
                                    if (!name.equals("l") && !name.equals("nl")) super.visit(name, value);
                                }
                            };
                        }
                        return delegate;
                    }
                    @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        return super.visitField(access, name, descriptor, signature, apiOnly ? null : value);
                    }
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                            @Override public void visitInsn(int opcode) {
                                if (opcode != Opcodes.NOP) super.visitInsn(opcode);
                            }
                        };
                    }
                };
                new ClassReader(Files.readAllBytes(file)).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES | (apiOnly ? ClassReader.SKIP_CODE : 0));
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(writer.toByteArray()));
                output.add(root.relativize(file).toString().replace('\\', '/') + " " + hash);
            }
        }
        Files.write(Path.of(args[1]), output);
        System.out.println(output.size() + " normalized classes: " + args[1]);
    }
}
