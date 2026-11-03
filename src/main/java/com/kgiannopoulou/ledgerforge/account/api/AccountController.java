package com.kgiannopoulou.ledgerforge.account.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.kgiannopoulou.ledgerforge.account.application.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
class AccountController {

    private final AccountService accountService;

    AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/api/v1/accounts")
    @PreAuthorize("@access.canOpenAccountFor(#request.customerId())")
    ResponseEntity<AccountResponse> open(@Valid @RequestBody CreateAccountRequest request) {
        var account = AccountResponse.from(accountService.openWallet(request.customerId(), request.currency()));
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id())).body(account);
    }

    @GetMapping("/api/v1/accounts/{id}")
    @PreAuthorize("@access.canReadAccount(#id)")
    AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.get(id));
    }

    @GetMapping("/api/v1/customers/{customerId}/accounts")
    @PreAuthorize("@access.canReadCustomer(#customerId)")
    List<AccountResponse> listForCustomer(@PathVariable UUID customerId) {
        return accountService.listForCustomer(customerId).stream().map(AccountResponse::from).toList();
    }

    @PostMapping("/api/v1/accounts/{id}/freeze")
    AccountResponse freeze(@PathVariable UUID id) {
        return AccountResponse.from(accountService.freeze(id));
    }

    @PostMapping("/api/v1/accounts/{id}/unfreeze")
    AccountResponse unfreeze(@PathVariable UUID id) {
        return AccountResponse.from(accountService.unfreeze(id));
    }

    @PostMapping("/api/v1/accounts/{id}/close")
    AccountResponse close(@PathVariable UUID id) {
        return AccountResponse.from(accountService.close(id));
    }
}
