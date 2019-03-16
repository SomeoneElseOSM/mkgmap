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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.mkgmap.scan.TokType;
import uk.me.parabola.mkgmap.scan.Token;
import uk.me.parabola.mkgmap.scan.TokenScanner;


/**
 * All the information contained in the options help file is held here.
 *
 * @author Steve Ratcliffe
 */
public class HelpOptions {

	private final List<HelpOptionItem> list = new ArrayList<HelpOptionItem>();
	private final Map<String, HelpOptionItem> options = new HashMap<String, HelpOptionItem>();

	/**
	 * Read the file given.
	 * @param stream An opened file stream to the help file.
	 */
	public void parse(InputStream stream) {
		InputStreamReader r = null;
		try {
			r = new InputStreamReader(stream, "utf-8");
			parse(r);
		} catch (UnsupportedEncodingException e) {
			// java guarantees utf-8 support, so this can't happen in normal circumstances
			throw new ExitException("unsupported encoding", e);
		} finally {
			Utils.closeFile(r);
		}
	}

	/**
	 * Read the given input stream and parse it.
	 */
	private void parse(InputStreamReader r) {
		TokenScanner scan = new TokenScanner("options", r);
		scan.setCommentChar(null);  // turn off comment processing which we don't use
		scan.setExtraWordChars("-");

		while (!scan.isEndOfFile()) {
			scan.skipSpace();
			Token tok = scan.peekToken();

			if (isOption(tok)) {
				parseOpt(scan);
			} else {
				parseSection(scan);
			}
		}
	}

	/**
	 * Parse a section of text between the options.
	 * This text starts in column 1.
	 *
	 * @param scan The token stream.
	 */
	private void parseSection(TokenScanner scan) {
		HelpOptionItem item = new HelpOptionItem();

		boolean para = false;
		while (!scan.isEndOfFile()) {

			Token tok = scan.peekToken();
			if (tok.isType(TokType.TEXT) && tok.getValue().startsWith("-")) {
				break;
			}

			if (para) {
				item.addDescriptionLine("");
				para = false;
			}

			if (tok.isType(TokType.EOL)) {
				scan.skipSpace();
				para = true;
			} else {
				item.addDescriptionLine(scan.readLine());
			}
		}

		list.add(item);
	}

	/**
	 * Parse a single option. An option can have several names (eg short and long form, or
	 * a deprecated version and a new name), and it is followed by a description that is
	 * indented by 4 spaces. There can be blank lines within the description.
	 *
	 * @param scan The token stream.
	 */
	private void parseOpt(TokenScanner scan) {
		HelpOptionItem item = new HelpOptionItem();

		while (!scan.isEndOfFile()) {
			String optname = scan.nextValue();

			if (optname.startsWith("--")) {
				optname = optname.substring(2);
			} else {
				optname = optname.substring(1);
			}

			readMeta(scan, item, optname);

			Token next = scan.peekToken();
			if (next.getType() != TokType.TEXT || !next.getValue().startsWith("-")) {
				parseDescription(scan, next, item);
				break;
			}
		}

		// Add a reference for each name
		for (String optname : item.getOptionNames())
			options.put(optname, item);

		// Add the ordered list
		list.add(item);
	}

	/**
	 * Read the meta variable for an option that takes an argument.
	 * @param scan The token stream.
	 * @param item The current option item.
	 * @param optname The name of the option.
	 */
	private void readMeta(TokenScanner scan, HelpOptionItem item, String optname) {
		String meta = null;
		boolean old = false;
		boolean removed = false;

		while (!scan.isEndOfFile()) {
			Token tok = scan.nextRawToken();

			if (tok.isType(TokType.EOL)) break;

			if (meta == null && (isSym(tok, "=") || tok.isWhiteSpace()))
				meta = scan.nextWord();
			else if (isSym(tok, "#")) {
				String cmd = scan.nextWord();
				if ("old".equals(cmd)) {
					old = true;
				} else if ("removed".equals(cmd)) {
					removed = true;
				}
			}
		}

		HelpOption opt = new HelpOption(optname, meta);
		opt.setOld(old);
		opt.setRemoved(removed);

		item.addOption(opt);
	}

	private boolean isSym(Token tok, String sym) {
		return tok.isType(TokType.SYMBOL) && tok.getValue().equals(sym);
	}

	/**
	 * Parse the description of a particular option. This is not for text that occurs between options.
	 *
	 * @param scan The token stream.
	 * @param next The next token in the stream, it has not been removed from the stream yet.
	 * @param item The current option item. The description is added here.
	 */
	private void parseDescription(TokenScanner scan, Token next, HelpOptionItem item) {
		boolean para = false;
		Token tok = next;
		while (!scan.isEndOfFile()) {

			if (tok.isType(TokType.TEXT)) {
				break;

			} else if (tok.isType(TokType.SPACE)) {
				if (para) {
					item.addDescriptionLine("");
					para = false;
				}

				tok = scan.nextRawToken();
				String val = tok.getValue();
				String line = "";
				if (val.length() > 4)
					line = val.substring(4);

				tok = scan.peekToken();
				if (tok.isValue("#")) {
					parseCommands(scan, item);
				} else {
					line += scan.readLine();
					item.addDescriptionLine(line);
				}
			} else if (tok.isType(TokType.EOL)) {
				scan.nextRawToken();
				para = true;
			} else {
				assert false : "unexpected";
			}

			tok = scan.peekToken();
		}
	}

	/**
	 * Read and interpret the commands.
	 * Commands follow the option description.
	 *
	 * @param scan Input stream
	 * @param item The current option.
	 */
	private void parseCommands(TokenScanner scan, HelpOptionItem item) {
		scan.validateNext("#");

		while (!scan.isEndOfFile()) {
			Token tok = scan.peekToken();

			if (tok.isType(TokType.EOL)) {
				return;
			} else if (tok.isType(TokType.TEXT)) {
				String cmd = scan.nextWord();

				if ("default".equals(cmd)) {
					scan.validateNext(":");
					String defaultValue = scan.nextWord();
					if (item.isBoolean()) {
						if ("on".equals(defaultValue))
							item.setDefaultValue("");
						else
							item.setDefaultValue(null);
					} else {
						item.setDefaultValue(defaultValue);
					}
				}
			} else {
				scan.nextRawToken();
			}
		}
	}

	/**
	 * Get a list of the long option names.
	 */
	public Set<String> getOptionNameSet() {
		Set<String> set = new HashSet<String>();
		for (HelpOptionItem item : list) {
			set.addAll(item.getOptionNames());
		}
		return set;
	}

	public HelpOptionItem getItemByName(String name) {
		return options.get(name);
	}

	public HelpOption getOptionByName(String name) {
		HelpOptionItem item = getItemByName(name);
		if (item == null)
			return null;
		return item.getOptionForName(name);
	}

	private boolean isOption(Token tok) {
		return tok.isText() && tok.getValue().startsWith("-");
	}
}
