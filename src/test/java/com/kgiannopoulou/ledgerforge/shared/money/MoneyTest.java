package com.kgiannopoulou.ledgerforge.shared.money;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalisesToCurrencyMinorUnits() {
        assertThat(Money.of("25", "EUR").amount()).isEqualByComparingTo("25.00");
        assertThat(Money.of("25", "EUR").amount().scale()).isEqualTo(2);
        assertThat(Money.of("25", "EUR")).isEqualTo(Money.of("25.00", "EUR"));
    }

    @Test
    void acceptsTrailingZerosBeyondMinorUnitsBecauseTheValueIsExact() {
        // NUMERIC(19,4) columns come back as 25.0000
        assertThat(Money.of("25.0000", "EUR")).isEqualTo(Money.of("25.00", "EUR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.005", "0.001", "1.234"})
    void rejectsAmountsThatWouldNeedRounding(String amount) {
        assertThatThrownBy(() -> Money.of(amount, "EUR"))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessageContaining("precision");
    }

    @Test
    void respectsCurrenciesWithoutDecimals() {
        assertThat(Money.of("100", "JPY").amount().scale()).isZero();
        assertThatThrownBy(() -> Money.of("100.5", "JPY")).isInstanceOf(InvalidMoneyException.class);
    }

    @Test
    void decimalArithmeticIsExactUnlikeDouble() {
        assertThat(0.1 + 0.2).isNotEqualTo(0.3); // the reason Money exists
        Money sum = Money.of("0.10", "EUR").add(Money.of("0.20", "EUR"));
        assertThat(sum).isEqualTo(Money.of("0.30", "EUR"));
    }

    @Test
    void addAndSubtract() {
        Money a = Money.of("100.00", "EUR");
        Money b = Money.of("30.55", "EUR");
        assertThat(a.subtract(b)).isEqualTo(Money.of("69.45", "EUR"));
        assertThat(b.subtract(a).isNegative()).isTrue();
        assertThat(a.add(b).toPlainString()).isEqualTo("130.55");
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.of("1", "EUR").add(Money.of("1", "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> Money.of("1", "EUR").compareTo(Money.of("1", "GBP")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void comparisons() {
        Money ten = Money.of("10", "EUR");
        Money five = Money.of("5", "EUR");
        assertThat(ten.isGreaterThan(five)).isTrue();
        assertThat(five.isLessThan(ten)).isTrue();
        assertThat(Money.zero(Currency.getInstance("EUR")).isZero()).isTrue();
        assertThat(ten.negate().isNegative()).isTrue();
        assertThat(ten.isPositive()).isTrue();
    }

    @Test
    void neverPrintsScientificNotation() {
        Money large = Money.of(new BigDecimal("1E+7"), Currency.getInstance("EUR"));
        assertThat(large.toPlainString()).isEqualTo("10000000.00");
        assertThat(large.toString()).isEqualTo("EUR 10000000.00");
    }

    @Test
    void requiresAmountAndCurrency() {
        assertThatThrownBy(() -> new Money(null, Currency.getInstance("EUR"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null)).isInstanceOf(NullPointerException.class);
    }
}
