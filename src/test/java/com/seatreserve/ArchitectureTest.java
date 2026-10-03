package com.seatreserve;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.seatreserve")
public class ArchitectureTest {

    @ArchTest
    static final ArchRule noHibernate = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..")
            .because("JPA/Hibernate is forbidden. Its write-behind flush reorders statements.");

    @ArchTest
    static final ArchRule integerMoneyOnly = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName(Double.class.getName())
            .orShould().dependOnClassesThat().haveFullyQualifiedName(Float.class.getName())
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.math.BigDecimal")
            .because("Money is integer paise (long) everywhere.");

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
            .orShould().dependOnClassesThat().resideInAnyPackage("java.sql..")
            .because("Domain must be pure Java");

    @ArchTest
    static final ArchRule retryNotOnTransactional = noClasses()
            .that().areAnnotatedWith(org.springframework.retry.annotation.Retryable.class)
            .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
            .because("Retry and Transactional on the same bean cause proxy issues or transaction bounds issues.");

    @ArchTest
    static final ArchRule inventoryImportsNoOtherContext = noClasses()
            .that().resideInAPackage("..inventory..")
            .should().dependOnClassesThat().resideInAnyPackage("..availability..", "..catalog..")
            .because("Inventory is the ledger and imports no other bounded context.");

    @ArchTest
    static final ArchRule availabilityNeverWrites = noClasses()
            .that().resideInAPackage("..availability..")
            .should().dependOnClassesThat().haveSimpleNameContaining("Repository")
            .orShould().dependOnClassesThat().haveSimpleNameContaining("Updater")
            .because("Availability context is purely for reads.");

    @ArchTest
    static final ArchRule portsAreInterfaces = classes()
            .that().resideInAPackage("..port..")
            .should().beInterfaces()
            .because("Ports must be interfaces.");
}
