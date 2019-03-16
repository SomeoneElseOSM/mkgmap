/*
 * Copyright (C) 2008, 2011.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */
package uk.me.parabola.mkgmap;

/**
 * An option or a key value pair.  Immutable class.
 */
public class Option {
	private final String option;
	private final String value;
	private final boolean experimental;
	private final boolean reset;
	private final boolean longOpt;

	protected Option(String optval) {
		this(optval.split("[:=]", 2));
	}

	public Option(String optname, String value) {
		String name = optname;
		String val = (value == null)? "": value;

		boolean reset = false;
		if (name.startsWith("no-")) {
			reset = true;
			name = name.substring(3);
			val = null;
		}

		boolean exp = false;
		if (name.startsWith("x-")) {
			exp = true;
			name = name.substring(2);
		}

		option = name;
		this.value = val;
		experimental = exp;
		this.reset = reset;
		if (name.length() > 1)
			longOpt = true;
		else
			longOpt = false;
	}

	private Option(String[] split) {
		this(split[0], (split.length > 1)? split[1]: null);
	}

	public String getOption() {
		return option;
	}

	public String getValue() {
		return value;
	}

	public boolean isExperimental() {
		return experimental;
	}

	public boolean isReset() {
		return reset;
	}

	public boolean isLongOpt() {
		return longOpt;
	}
}
