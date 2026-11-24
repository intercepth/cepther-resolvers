package com.cepther.resolvers;

import java.net.URI;

/**
 * One site (or family of URL shapes on a site). Implementations must be stateless and thread-safe;
 * everything they need (HTTP, captcha, options) comes from the {@link ResolveContext}.
 */
public interface Resolver {

    /** Short stable id, e.g. "gdrive-folder". */
    String name();

    /** Cheap, offline check: does this resolver understand this URL shape? */
    boolean matches(URI url);

    ResolveResult resolve(URI url, ResolveContext ctx) throws ResolveException;
}
