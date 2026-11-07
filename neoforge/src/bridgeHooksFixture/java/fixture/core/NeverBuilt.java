package fixture.core;

import fixture.api.SuperOnlyEvent;

/** Its constructor calls super(), which isn't building a SuperOnlyEvent. */
public class NeverBuilt extends SuperOnlyEvent {}
