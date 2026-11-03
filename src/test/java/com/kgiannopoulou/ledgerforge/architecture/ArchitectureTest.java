package com.kgiannopoulou.ledgerforge.architecture;

import com.kgiannopoulou.ledgerforge.account.domain.Account;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Executable architecture rules. The package structure is only worth something if it is
 * enforced; these rules fail the build when a shortcut breaks it.
 */
@AnalyzeClasses(packages = "com.kgiannopoulou.ledgerforge", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.kgiannopoulou.ledgerforge";

    @ArchTest
    static final ArchRule feature_modules_are_free_of_cycles =
            slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("..api..", "..application..", "..infrastructure..")
                    .because("the domain model is the core; adapters depend on it, never the reverse");

    @ArchTest
    static final ArchRule domain_does_not_know_about_http =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule application_does_not_depend_on_api_or_adapters =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage("..api..", "..infrastructure..");

    @ArchTest
    static final ArchRule infrastructure_is_only_used_by_infrastructure =
            noClasses().that().resideOutsideOfPackage("..infrastructure..")
                    .should().dependOnClassesThat().resideInAPackage(ROOT + ".*.infrastructure..")
                    .because("adapters are wired by Spring through domain/application interfaces");

    @ArchTest
    static final ArchRule shared_kernel_does_not_depend_on_features =
            noClasses().that().resideInAPackage(ROOT + ".shared..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            ROOT + ".account..", ROOT + ".customer..", ROOT + ".ledger..", ROOT + ".payment..",
                            ROOT + ".transfer..", ROOT + ".idempotency..", ROOT + ".audit..", ROOT + ".outbox..",
                            ROOT + ".messaging..", ROOT + ".risk..", ROOT + ".webhook..", ROOT + ".security..");

    @ArchTest
    static final ArchRule controllers_live_in_api_packages =
            classes().that().areAnnotatedWith(RestController.class).should().resideInAPackage("..api..");

    @ArchTest
    static final ArchRule only_the_ledger_moves_money =
            noClasses().that().resideOutsideOfPackage(ROOT + ".ledger..")
                    .should().callMethod(Account.class, "debit", Money.class)
                    .orShould().callMethod(Account.class, "credit", Money.class)
                    .because("every balance change must be part of a balanced ledger transaction");

    @ArchTest
    static final ArchRule no_floating_point_money =
            noFields().should().haveRawType(double.class)
                    .orShould().haveRawType(float.class)
                    .orShould().haveRawType(Double.class)
                    .orShould().haveRawType(Float.class)
                    .because("binary floating point cannot represent decimal money exactly");

    @ArchTest
    static final ArchRule amounts_are_big_decimals =
            fields().that().haveNameMatching("(?i).*amount|balance").and().areDeclaredInClassesThat()
                    .resideInAPackage("..domain..")
                    .should().haveRawType(BigDecimal.class).orShould().haveRawType(Money.class);

    @ArchTest
    static final ArchRule constructor_injection_only =
            noFields().should().beAnnotatedWith(Autowired.class)
                    .because("constructor injection makes dependencies explicit and objects immutable");
}
