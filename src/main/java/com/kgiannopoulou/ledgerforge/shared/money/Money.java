package com.kgiannopoulou.ledgerforge.shared.money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable monetary amount in a single currency.
 *
 * <p>Uses {@link BigDecimal} rather than {@code double}: binary floating point cannot represent
 * most decimal fractions exactly ({@code 0.1 + 0.2 != 0.3}), which is unacceptable for money.
 *
 * <p>The amount is normalised to the currency's minor-unit scale (2 for EUR, 0 for JPY), so
 * {@code Money.of("25", "EUR")} and {@code Money.of("25.00", "EUR")} are {@code equals}. Values that
 * would need rounding (e.g. {@code 10.005 EUR}) are rejected instead of being silently rounded.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");

        int minorUnits = currency.getDefaultFractionDigits();
        if (minorUnits < 0) {
            throw new InvalidMoneyException("Currency " + currency + " has no minor unit and is not supported");
        }
        // stripTrailingZeros() so that "25.0000" read back from NUMERIC(19,4) is accepted as 25.00.
        if (amount.stripTrailingZeros().scale() > minorUnits) {
            throw new InvalidMoneyException(
                    "Invalid monetary precision: %s allows at most %d decimal places, got %s"
                            .formatted(currency, minorUnits, amount.toPlainString()));
        }
        amount = amount.setScale(minorUnits);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    public boolean hasSameCurrencyAs(Money other) {
        return currency.equals(other.currency);
    }

    /** Plain decimal string for APIs, e.g. {@code "25.00"} (never scientific notation). */
    public String toPlainString() {
        return amount.toPlainString();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!hasSameCurrencyAs(other)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }
}
