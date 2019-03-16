/*
 * Copyright (C) 2013.
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
package uk.me.parabola.mkgmap.main;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import uk.me.parabola.mkgmap.Option;

/**
 * The help file information about a single option.
 *
 * There can be several option names for the same option and some names can be marked as being
 * old or removed.
 *
 * A help item has a description, that is stored as a set of lines of text.
 * A help item can have a default value associated with it.
 *
 * @see HelpOptions
 *
 * @author Steve Ratcliffe
 */
public class HelpOptionItem {
	protected final List<String> descriptionLines = new ArrayList<String>();
	private final List<HelpOption> options = new ArrayList<HelpOption>();

	private String defaultValue;

	public void addOption(HelpOption opt) {
		options.add(opt);
	}

	public void addDescriptionLine(String line) {
		descriptionLines.add(line);
	}

	/**
	 * Builds and returns the description from the set of saved lines.
	 */
	public String getDescription() {
		if (descriptionLines.isEmpty())
			return "";

		StringBuilder sb = new StringBuilder();
		for (String l : descriptionLines) {
			sb.append(l);
			sb.append('\n');
		}
		sb.deleteCharAt(sb.length() - 1);
		return sb.toString();
	}

	private boolean hasOptions() {
		return !options.isEmpty();
	}

	/**
	 * Get all the names for this help option.
	 * XXX TODO this needs to be ordered. First one is the 'real' name of the option.
	 * @return A set of names.
	 */
	public Set<String> getOptionNames() {
		Set<String> set = new HashSet<String>();
		for (Option opt : options) {
			String optionName = opt.getOption();
			set.add(optionName);
		}
		return set;
	}

	/**
	 * Get the option object for a given option name.
	 *
	 * There can be several names for the same option, used when options are renamed etc.
	 *
	 * @param name The option name.
	 * @return The details for that name.
	 */
	public HelpOption getOptionForName(String name) {
		for (HelpOption opt : options) {
			if (opt.getOption().equals(name))
				return opt;
		}
		return null;
	}

	/**
	 * Is this a boolean option or not?
	 * @return True if this is a boolean option, in other words if it does not take an argument.
	 */
	public boolean isBoolean() {
		Option option = options.get(0);
		return option.getValue() == null || option.getValue().isEmpty();
	}

	public String getDefault() {
		return defaultValue;
	}

	public void setDefaultValue(String defaultValue) {
		this.defaultValue = defaultValue;
	}

	/**
	 * The META variable is the name that stands for the value you give to the option.
	 *
	 * So --output=FILE then FILE is the META name.
	 *
	 * @param optname The option name. Used when the same option has more than one name.
	 */
	public String getMeta(String optname) {
		return getOptionForName(optname).getValue();
	}

	/**
	 * Return a formatted string for this help item suitable for display.
	 */
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (Option opt : options) {
			String key = opt.getOption();

			sb.append(opt.isLongOpt() ? "--" : "-");
			sb.append(key);

			String val = opt.getValue();
			if (val != null && !val.isEmpty()) {
				sb.append(opt.isLongOpt()? "=": " ");
				sb.append(val);
			}

			sb.append('\n');
		}

		for (String line : descriptionLines) {
			if (hasOptions())
				sb.append("    ");
			sb.append(line);
			sb.append('\n');
		}
		return sb.toString();
	}
}
