package com.kgiannopoulou.ledgerforge.customer.infrastructure;

import com.kgiannopoulou.ledgerforge.customer.domain.Customer;
import com.kgiannopoulou.ledgerforge.customer.domain.CustomerRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data adapter for {@link CustomerRepository}. Spring Data implements the port's methods
 * because their signatures match {@link JpaRepository} / derived-query conventions.
 */
interface JpaCustomerRepository extends JpaRepository<Customer, UUID>, CustomerRepository {
}
