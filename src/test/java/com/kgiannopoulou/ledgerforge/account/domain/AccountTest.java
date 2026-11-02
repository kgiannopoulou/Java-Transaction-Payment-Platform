package com.kgiannopoulou.ledgerforge.account.domain;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;
import com.kgiannopoulou.ledgerforge.shared.error.ConflictException;
import com.kgiannopoulou.ledgerforge.shared.money.CurrencyMismatchException;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    private final Account wallet = Account.openWallet(UUID.randomUUID(), EUR, Instant.now());

    @Test
    void opensActiveWithZeroBalance() {
        assertThat(wallet.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(wallet.getBalance()).isEqualTo(Money.zero(EUR));
        assertThat(wallet.getType()).isEqualTo(AccountType.CUSTOMER_WALLET);
    }

    @Test
    void creditsAndDebits() {
        wallet.credit(eur("100"));
        wallet.debit(eur("40.50"));
        assertThat(wallet.getBalance()).isEqualTo(eur("59.50"));
    }

    @Test
    void walletCannotGoNegative() {
        wallet.credit(eur("10"));
        assertThatThrownBy(() -> wallet.debit(eur("10.01"))).isInstanceOf(InsufficientFundsException.class);
        assertThat(wallet.getBalance()).isEqualTo(eur("10.00"));
        assertThat(wallet.canCover(eur("10"))).isTrue();
        assertThat(wallet.canCover(eur("10.01"))).isFalse();
    }

    @Test
    void rejectsForeignCurrency() {
        assertThatThrownBy(() -> wallet.credit(Money.of("1", "USD"))).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void frozenAccountsCannotMoveMoney() {
        wallet.freeze();
        assertThatThrownBy(wallet::requireActive)
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("FROZEN");
        wallet.unfreeze();
        wallet.requireActive();
    }

    @Test
    void closingRequiresZeroBalanceAndIsFinal() {
        wallet.credit(eur("1"));
        assertThatThrownBy(wallet::close).isInstanceOf(BusinessRuleViolationException.class);
        wallet.debit(eur("1"));
        wallet.close();
        assertThat(wallet.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThatThrownBy(wallet::unfreeze).isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyExternalFundingMayGoNegative() {
        assertThat(AccountType.EXTERNAL_FUNDING.allowsNegativeBalance()).isTrue();
        assertThat(AccountType.CUSTOMER_WALLET.allowsNegativeBalance()).isFalse();
        assertThat(AccountType.PAYMENT_HOLDS.allowsNegativeBalance()).isFalse();
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }
}
