package com.dslrcamera.client;

import java.util.List;

/** Photo size in map tiles. Each tile is one 128x128 map in an item frame. */
public record PrintSize(int cols, int rows) {
	public static final List<PrintSize> ALL = List.of(
		new PrintSize(1, 1), new PrintSize(2, 1), new PrintSize(1, 2), new PrintSize(2, 2),
		new PrintSize(3, 2), new PrintSize(2, 3), new PrintSize(3, 3), new PrintSize(4, 3), new PrintSize(3, 4));

	public int tiles() {
		return cols * rows;
	}

	public String label() {
		return cols + "×" + rows;
	}
}
