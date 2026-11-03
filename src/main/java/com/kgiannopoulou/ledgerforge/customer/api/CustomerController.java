package com.kgiannopoulou.ledgerforge.customer.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.kgiannopoulou.ledgerforge.customer.application.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
class CustomerController {

    private final CustomerService customerService;

    CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    ResponseEntity<CustomerResponse> register(@Valid @RequestBody CreateCustomerRequest request) {
        var customer = CustomerResponse.from(customerService.register(request.email(), request.fullName()));
        return ResponseEntity.created(URI.create("/api/v1/customers/" + customer.id())).body(customer);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.canReadCustomer(#id)")
    CustomerResponse get(@PathVariable UUID id) {
        return CustomerResponse.from(customerService.get(id));
    }
}
