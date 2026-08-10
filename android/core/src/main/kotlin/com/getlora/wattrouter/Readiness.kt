// Readiness.kt: what is switched on, and where to switch on the rest.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Needed     One thing that has to be on.
//   Readiness  All of them, as they stand now.
//
// A checklist rather than a wizard, and that is the decision. Every one of
// these can be revoked from Settings while the app is not looking (the
// accessibility service, notifications, the calendar, contacts, location) so a
// one-way flow that congratulates somebody once and never looks again is a flow
// that lies the first time anything is switched off. Permission.kt refuses to
// cache its own state for exactly this reason (#229); this is the same rule at
// the scale of a screen.
//
// The restricted-settings step is the one that has no error attached anywhere
// else. On a sideloaded build the accessibility toggle is visible, greyed, and
// unexplained, and every phone-driving tool is present and does nothing.

package com.getlora.wattrouter

/** One thing that has to be on, and where. */
data class Needed(
    /** What it is, in the person's terms rather than the platform's. */
    val what: String,
    /** Where they turn it on, named exactly. "Settings" alone is not advice. */
    val where: String,
    val isOn: Boolean,
    /**
     * Whether the agent is useless without it.
     *
     * The calendar is not: an agent that cannot see a diary can still drive a
     * phone. The accessibility service is, and saying so is what stops a list
     * of five amber rows reading as five equal problems.
     */
    val isRequired: Boolean = false,
)

/** Everything that has to be on, as it stands right now. */
data class Readiness(val steps: List<Needed>) {
    /** Whether the phone-driving half works at all. */
    val canDrive: Boolean get() = steps.none { it.isRequired && !it.isOn }

    /** What to do next, or null when there is nothing. */
    val next: Needed? get() = steps.firstOrNull { it.isRequired && !it.isOn }
        ?: steps.firstOrNull { !it.isOn }

    companion object {
        /**
         * The list, given what the phone says.
         *
         * @param sideloaded whether this build was installed outside a store.
         *   The restricted-settings step is shown only then, because on a
         *   store install the toggle simply works and a step nobody needs is a
         *   step that makes the other four look optional too.
         */
        fun of(
            driving: Boolean,
            notifying: Boolean,
            calendar: Boolean,
            contacts: Boolean,
            location: Boolean,
            sideloaded: Boolean,
        ): Readiness = Readiness(
            buildList {
                // First, and before the switch it unblocks: somebody who reads
                // the accessibility row, goes to Settings and finds it greyed
                // has been sent to a dead end by this very screen.
                if (sideloaded && !driving) {
                    add(
                        Needed(
                            what = "Allow restricted settings for this app",
                            where = "Settings > Apps > WattRouter > ⋮ (top right) > " +
                                "Allow restricted settings",
                            isOn = false,
                            isRequired = true,
                        ),
                    )
                }
                add(
                    Needed(
                        what = "Let the assistant see and use the screen",
                        where = "Settings > Accessibility > WattRouter",
                        isOn = driving,
                        isRequired = true,
                    ),
                )
                add(
                    Needed(
                        what = "Show what it is doing while it works",
                        where = "Settings > Apps > WattRouter > Notifications",
                        isOn = notifying,
                    ),
                )
                add(Needed("Read the calendar", CALENDAR, calendar))
                add(Needed("Look somebody up in contacts", CONTACTS, contacts))
                add(Needed("Say roughly where you are", LOCATION, location))
            },
        )

        private const val CALENDAR = "Settings > Apps > WattRouter > Permissions > Calendar"
        private const val CONTACTS = "Settings > Apps > WattRouter > Permissions > Contacts"
        private const val LOCATION = "Settings > Apps > WattRouter > Permissions > Location"
    }
}
