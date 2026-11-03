/**
 * Authentication (stateless JWT bearer tokens) and authorization.
 *
 * <p>Two layers:
 * <ol>
 *   <li><b>Role gates</b> per endpoint in {@code SecurityConfiguration}, e.g. only CUSTOMERs
 *       move money and only ADMINs freeze accounts.</li>
 *   <li><b>Ownership checks</b> per resource in {@code AccessPolicy}, referenced from
 *       {@code @PreAuthorize} on controllers, e.g. a customer may only read their own accounts.</li>
 * </ol>
 * Feature modules do not depend on this package at compile time; the controllers only name the
 * {@code access} bean in SpEL expressions. See {@code docs/security.md}.
 */
package com.kgiannopoulou.ledgerforge.security;
