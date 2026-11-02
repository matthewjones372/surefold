package dev.surefold.app

/**
 * The database is at its migrations' latest version, whichever tool ran them; [applied] is how many they applied.
 *
 * A node that reads the database takes one of these, which makes "after the migrations" an edge in the graph rather
 * than a line in a comment: the reader cannot be built before the writer has run, and `render()` draws the ordering.
 */
class Migrated(val applied: Int)
