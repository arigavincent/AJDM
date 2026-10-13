package io.ajdm.verify;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class BackendArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("io.ajdm");

    @Test void domainHasNoInfrastructureDependencies() {
        forbidden("model", "net", "store", "engine", "media", "core", "ipc", "cli", "ui");
        noClasses().that().resideInAPackage("io.ajdm.model..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "java.net.http..", "javafx..", "com.google.gson..")
                .check(CLASSES);
    }
    @Test void lowerLayersDoNotDependOnTheirConsumers() {
        forbidden("net", "store", "engine", "media", "core", "ipc", "cli", "ui");
        forbidden("store", "net", "engine", "media", "core", "ipc", "cli", "ui");
        forbidden("media", "net", "store", "engine", "core", "ipc", "cli", "ui");
        forbidden("engine", "media", "core", "ipc", "cli", "ui");
        forbidden("core", "ipc", "cli", "ui");
        forbidden("ipc", "cli", "ui");
    }
    @Test void desktopClientCannotOwnDownloadsOrTheDatabase() {
        forbidden("ui", "engine", "store", "net");
        noClasses().that().resideInAPackage("io.ajdm.ui..")
                .should().callConstructorWhere(new DescribedPredicate<JavaConstructorCall>("create a CoreService") {
                    @Override public boolean test(JavaConstructorCall call) {
                        return call.getTargetOwner().getName().equals("io.ajdm.core.CoreService");
                    }
                })
                .check(CLASSES);
        noClasses().that().resideInAPackage("io.ajdm.ui..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "java.net.http..")
                .check(CLASSES);
    }
    @Test void payloadEngineCannotSpawnExternalDownloaders() {
        noClasses().that().resideInAnyPackage("io.ajdm.engine..", "io.ajdm.net..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(CLASSES);
        noClasses().that().resideInAnyPackage("io.ajdm.engine..", "io.ajdm.net..")
                .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("spawn processes through Runtime.exec") {
                    @Override public boolean test(JavaMethodCall call) {
                        return call.getTargetOwner().getName().equals("java.lang.Runtime") && call.getTarget().getName().equals("exec");
                    }
                })
                .check(CLASSES);
    }
    @Test void backendCannotDependOnJavafx() {
        noClasses().that().resideInAnyPackage("io.ajdm.model..", "io.ajdm.net..", "io.ajdm.store..",
                "io.ajdm.engine..", "io.ajdm.media..", "io.ajdm.core..", "io.ajdm.ipc..", "io.ajdm.cli..")
                .should().dependOnClassesThat().resideInAPackage("javafx..").check(CLASSES);
    }
    private static void forbidden(String layer, String... consumers) {
        String[] packages = java.util.Arrays.stream(consumers).map(name -> "io.ajdm." + name + "..").toArray(String[]::new);
        noClasses().that().resideInAPackage("io.ajdm." + layer + "..")
                .should().dependOnClassesThat().resideInAnyPackage(packages).check(CLASSES);
    }
}
