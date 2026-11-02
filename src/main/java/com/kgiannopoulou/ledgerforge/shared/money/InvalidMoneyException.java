package com.kgiannopoulou.ledgerforge.shared.money;

import com.kgiannopoulou.ledgerforge.shared.error.InvalidRequestException;

/** A monetary amount that cannot be represented exactly in its currency. */
public class InvalidMoneyException extends InvalidRequestException {

    public InvalidMoneyException(String message) {
        super("invalid_amount", message);
    }
}
