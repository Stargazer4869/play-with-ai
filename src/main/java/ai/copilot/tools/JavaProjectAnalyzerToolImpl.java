package ai.copilot.tools;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class JavaProjectAnalyzerToolImpl implements JavaProjectAnalyzerTool {

    private static final Logger logger = LoggerFactory.getLogger(JavaProjectAnalyzerToolImpl.class);
    private static final String DEFAULT_CLASSES_DIRECTORY = "target/classes";

    private final Path workspaceRoot;

    public JavaProjectAnalyzerToolImpl(@Qualifier("copilotWorkspaceRoot") Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    @Tool(description = "Analyze compiled Java classes and summarize packages, classes, public fields, and public methods. Leave the input blank to analyze the current project's target/classes directory. Optionally provide an absolute path to a jar file or compiled classes directory.")
    public String analyzeProject(String location) {
        logger.info("Copilot tool analyzeProject used with target={}", summarizeLocation(location));
        try {
            Path target = resolveTarget(location);
            List<ClassSummary> classes = Files.isDirectory(target)
                    ? analyzeClassesDirectory(target)
                    : analyzeJarFile(target);

            if (classes.isEmpty()) {
                return "No top-level compiled classes were found in %s.".formatted(target);
            }

            return format(classes);
        }
        catch (Exception exception) {
            return "Unable to analyze Java project structure: " + exception.getMessage();
        }
    }

    private String summarizeLocation(String location) {
        return location == null || location.isBlank()
                ? "<default:" + DEFAULT_CLASSES_DIRECTORY + ">"
                : location.trim();
    }

    private Path resolveTarget(String location) {
        if (location == null || location.isBlank()) {
            Path defaultTarget = workspaceRoot.resolve(DEFAULT_CLASSES_DIRECTORY).normalize();
            if (!Files.exists(defaultTarget)) {
                throw new IllegalArgumentException("Default classes directory does not exist: %s. Build the project first.".formatted(defaultTarget));
            }
            return defaultTarget;
        }

        Path providedPath = Path.of(location.trim()).normalize();
        if (!providedPath.isAbsolute()) {
            throw new IllegalArgumentException("The optional analysis path must be absolute.");
        }
        if (!Files.exists(providedPath)) {
            throw new IllegalArgumentException("Path does not exist: " + providedPath);
        }
        if (Files.isRegularFile(providedPath) && !providedPath.toString().endsWith(".jar")) {
            throw new IllegalArgumentException("Expected a jar file or a compiled classes directory.");
        }
        return providedPath;
    }

    private List<ClassSummary> analyzeClassesDirectory(Path classesDirectory) throws IOException {
        try (Stream<Path> stream = Files.walk(classesDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .sorted()
                    .map(this::readClassSummary)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
    }

    private List<ClassSummary> analyzeJarFile(Path jarFile) throws IOException {
        List<ClassSummary> classes = new ArrayList<>();
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            List<JarEntry> entries = jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .toList();

            for (JarEntry entry : entries) {
                try (InputStream inputStream = jar.getInputStream(entry)) {
                    ClassSummary summary = readClassSummary(inputStream.readAllBytes());
                    if (summary != null) {
                        classes.add(summary);
                    }
                }
            }
        }
        return classes;
    }

    private ClassSummary readClassSummary(Path classFile) {
        try {
            return readClassSummary(Files.readAllBytes(classFile));
        }
        catch (IOException exception) {
            return null;
        }
    }

    private ClassSummary readClassSummary(byte[] bytecode) {
        ClassReader classReader = new ClassReader(bytecode);
        SummaryClassVisitor visitor = new SummaryClassVisitor();
        classReader.accept(visitor, ClassReader.SKIP_FRAMES);
        return visitor.build();
    }

    private String format(List<ClassSummary> classes) {
        Map<String, List<ClassSummary>> byPackage = classes.stream()
                .sorted(Comparator.comparing(ClassSummary::packageName).thenComparing(ClassSummary::className))
                .collect(Collectors.groupingBy(ClassSummary::packageName, LinkedHashMap::new, Collectors.toList()));

        StringBuilder output = new StringBuilder();
        boolean firstPackage = true;
        for (Map.Entry<String, List<ClassSummary>> entry : byPackage.entrySet()) {
            if (!firstPackage) {
                output.append(System.lineSeparator());
            }
            firstPackage = false;
            output.append(entry.getKey());
            for (ClassSummary classSummary : entry.getValue()) {
                output.append(System.lineSeparator()).append("\t").append(classSummary.className());
                for (String field : classSummary.fields()) {
                    output.append(System.lineSeparator()).append("\t\t").append(field);
                }
                for (String method : classSummary.methods()) {
                    output.append(System.lineSeparator()).append("\t\t").append(method);
                }
            }
        }
        return output.toString();
    }

    private static final class SummaryClassVisitor extends ClassVisitor {

        private String packageName;
        private String className;
        private boolean include;
        private final List<String> fields = new ArrayList<>();
        private final List<String> methods = new ArrayList<>();

        private SummaryClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            String fqcn = name.replace('/', '.');
            int packageSeparator = fqcn.lastIndexOf('.');
            this.packageName = packageSeparator >= 0 ? fqcn.substring(0, packageSeparator) : "(default package)";
            this.className = packageSeparator >= 0 ? fqcn.substring(packageSeparator + 1) : fqcn;
            this.include = !className.contains("$") && !"module-info".equals(className) && !"package-info".equals(className);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (include && Modifier.isPublic(access) && (access & Opcodes.ACC_SYNTHETIC) == 0) {
                fields.add(formatField(access, name, descriptor));
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (include
                    && Modifier.isPublic(access)
                    && !name.equals("<init>")
                    && !name.equals("<clinit>")
                    && (access & Opcodes.ACC_SYNTHETIC) == 0
                    && (access & Opcodes.ACC_BRIDGE) == 0) {
                return new SummaryMethodVisitor(access, name, descriptor, methods);
            }
            return null;
        }

        private ClassSummary build() {
            if (!include) {
                return null;
            }
            fields.sort(String::compareTo);
            methods.sort(String::compareTo);
            return new ClassSummary(packageName, className, List.copyOf(fields), List.copyOf(methods));
        }

        private String formatField(int access, String name, String descriptor) {
            return "%s %s %s;".formatted(formatModifiers(access), simpleType(Type.getType(descriptor)), name).trim();
        }
    }

    private static final class SummaryMethodVisitor extends MethodVisitor {

        private final int access;
        private final String methodName;
        private final Type methodType;
        private final List<String> methods;
        private final List<String> parameterNames;
        private final Map<Integer, Integer> parameterSlotToIndex = new LinkedHashMap<>();
        private int seenParameters;

        private SummaryMethodVisitor(int access, String methodName, String descriptor, List<String> methods) {
            super(Opcodes.ASM9);
            this.access = access;
            this.methodName = methodName;
            this.methodType = Type.getMethodType(descriptor);
            this.methods = methods;
            this.parameterNames = new ArrayList<>();
            this.seenParameters = 0;
            initializeParameterSlots();
            for (int index = 0; index < methodType.getArgumentTypes().length; index++) {
                parameterNames.add("arg" + index);
            }
        }

        @Override
        public void visitParameter(String name, int access) {
            if (seenParameters < parameterNames.size()) {
                parameterNames.set(seenParameters, name == null || name.isBlank() ? "arg" + seenParameters : name);
            }
            seenParameters++;
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
            Integer parameterIndex = parameterSlotToIndex.get(index);
            if (parameterIndex != null && parameterIndex < parameterNames.size()) {
                String current = parameterNames.get(parameterIndex);
                if (current.startsWith("arg") && name != null && !name.isBlank()) {
                    parameterNames.set(parameterIndex, name);
                }
            }
        }

        @Override
        public void visitEnd() {
            methods.add(formatMethod());
        }

        private void initializeParameterSlots() {
            int slotIndex = Modifier.isStatic(access) ? 0 : 1;
            Type[] argumentTypes = methodType.getArgumentTypes();
            for (int argumentIndex = 0; argumentIndex < argumentTypes.length; argumentIndex++) {
                parameterSlotToIndex.put(slotIndex, argumentIndex);
                slotIndex += argumentTypes[argumentIndex].getSize();
            }
        }

        private String formatMethod() {
            Type[] argumentTypes = methodType.getArgumentTypes();
            List<String> parts = new ArrayList<>();
            for (int index = 0; index < argumentTypes.length; index++) {
                parts.add(simpleType(argumentTypes[index]) + " " + parameterNames.get(index));
            }
            return "%s %s %s(%s);".formatted(formatModifiers(access), simpleType(methodType.getReturnType()), methodName, String.join(", ", parts)).trim();
        }
    }

    private static String formatModifiers(int access) {
        int javaModifiers = 0;
        if ((access & Opcodes.ACC_PUBLIC) != 0) javaModifiers |= Modifier.PUBLIC;
        if ((access & Opcodes.ACC_STATIC) != 0) javaModifiers |= Modifier.STATIC;
        if ((access & Opcodes.ACC_FINAL) != 0) javaModifiers |= Modifier.FINAL;
        if ((access & Opcodes.ACC_ABSTRACT) != 0) javaModifiers |= Modifier.ABSTRACT;
        if ((access & Opcodes.ACC_NATIVE) != 0) javaModifiers |= Modifier.NATIVE;
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) javaModifiers |= Modifier.SYNCHRONIZED;
        if ((access & Opcodes.ACC_TRANSIENT) != 0) javaModifiers |= Modifier.TRANSIENT;
        if ((access & Opcodes.ACC_VOLATILE) != 0) javaModifiers |= Modifier.VOLATILE;
        if ((access & Opcodes.ACC_STRICT) != 0) javaModifiers |= Modifier.STRICT;
        return Modifier.toString(javaModifiers);
    }

    private static String simpleType(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> "void";
            case Type.BOOLEAN -> "boolean";
            case Type.CHAR -> "char";
            case Type.BYTE -> "byte";
            case Type.SHORT -> "short";
            case Type.INT -> "int";
            case Type.FLOAT -> "float";
            case Type.LONG -> "long";
            case Type.DOUBLE -> "double";
            case Type.ARRAY -> simpleType(type.getElementType()) + "[]".repeat(type.getDimensions());
            case Type.OBJECT -> {
                String className = type.getClassName();
                int separator = className.lastIndexOf('.');
                yield separator >= 0 ? className.substring(separator + 1) : className;
            }
            default -> type.getClassName();
        };
    }

    private record ClassSummary(String packageName, String className, List<String> fields, List<String> methods) {
    }
}

