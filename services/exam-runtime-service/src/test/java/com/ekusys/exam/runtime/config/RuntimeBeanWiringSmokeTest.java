package com.ekusys.exam.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Bean 装配冒烟测试：扫描全部 Spring 组件源码，确保每个组件要么只有一个构造函数，
 * 要么多个构造函数中恰有一个标注 @Autowired。防止再次出现
 * "No default constructor found" 的启动阻断缺陷（单元测试不启动完整上下文时无法暴露）。
 */
class RuntimeBeanWiringSmokeTest {

    private static final String STEREOTYPE_ANNOTATION = "org.springframework.stereotype.";

    @Test
    void everySpringComponentHasResolvableConstructor() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Class<?> component : scanComponents()) {
            Constructor<?>[] constructors = component.getDeclaredConstructors();
            if (constructors.length <= 1) {
                continue;
            }
            long autowiredCount = Stream.of(constructors)
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count();
            if (autowiredCount != 1) {
                violations.add(component.getName()
                    + " has " + constructors.length + " constructors and "
                    + autowiredCount + " @Autowired annotations");
            }
        }
        assertThat(violations)
            .as("Spring components with ambiguous constructors")
            .isEmpty();
    }

    private List<Class<?>> scanComponents() throws IOException {
        Path sourceRoot = Paths.get("src", "main", "java", "com", "ekusys", "exam", "runtime");
        assertThat(sourceRoot).exists();
        List<Class<?>> components = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                .map(this::loadClass)
                .filter(java.util.Objects::nonNull)
                .filter(this::isSpringComponent)
                .filter(clazz -> !clazz.isEnum() && !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers()))
                .forEach(components::add);
        }
        assertThat(components).hasSizeGreaterThan(20);
        return components;
    }

    private Class<?> loadClass(Path sourceFile) {
        String relative = sourceRootRelativize(sourceFile);
        if (relative == null) {
            return null;
        }
        String className = "com.ekusys.exam.runtime."
            + relative.replace('/', '.').replace("\\", ".").replace(".java", "");
        try {
            return Class.forName(className);
        } catch (Throwable ClassNotFoundExceptionOrLinkageError) {
            return null;
        }
    }

    private String sourceRootRelativize(Path sourceFile) {
        Path root = Paths.get("src", "main", "java", "com", "ekusys", "exam", "runtime");
        Path normalized = root.relativize(sourceFile);
        return normalized.toString();
    }

    private boolean isSpringComponent(Class<?> clazz) {
        for (java.lang.annotation.Annotation annotation : clazz.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.startsWith(STEREOTYPE_ANNOTATION)) {
                return true;
            }
        }
        return false;
    }
}
