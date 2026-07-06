package com.d2os.artifacts;

/** The Case has no delivered package yet (contracts/api.yaml: "Case not yet Delivered") → HTTP 409. */
public class PackageNotDeliveredException extends RuntimeException {
    public PackageNotDeliveredException(String message) {
        super(message);
    }
}
