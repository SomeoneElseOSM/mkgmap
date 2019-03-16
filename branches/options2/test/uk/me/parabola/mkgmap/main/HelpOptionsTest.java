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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import func.lib.TestUtils;
import org.junit.Test;

import static org.junit.Assert.*;

public class HelpOptionsTest {

	@Test
	public void testShortName() throws IOException {
		HelpOptions ho = parseOptions("-c\n    Single letter option\n");

		Set<String> nameSet = ho.getOptionNameSet();
		assertTrue(nameSet.contains("c"));

		HelpOptionItem item = ho.getItemByName("c");
		assertEquals("Single letter option", item.getDescription());
	}

	@Test
	public void testLongName()  {
		HelpOptions ho = parseOptions("--long-name\n    A long name option\n");

		Set<String> nameSet = ho.getOptionNameSet();
		assertTrue(nameSet.contains("long-name"));

		HelpOptionItem item = ho.getItemByName("long-name");
		assertEquals("A long name option", item.getDescription());
	}

	@Test
	public void testOptionWithTwoNames() {
		HelpOptions ho = parseOptions("-l\n--long-name\n    A long name option\n");

		HelpOptionItem item = ho.getItemByName("l");
		assertNotNull(item);
		HelpOptionItem item2 = ho.getItemByName("long-name");
		assertNotNull(item2);
		assertEquals(item, item2);
	}

	@Test
	public void testShortOptWithValue() {
		HelpOptions ho = parseOptions("-c file\n    Single letter option\n");

		HelpOptionItem item = ho.getItemByName("c");
		assertNotNull(item);

		assertEquals("file", item.getMeta("c"));
	}

	@Test
	public void testLongOptWithValue() {
		HelpOptions ho = parseOptions("-long-opt=file\n    With meta\n");

		HelpOptionItem item = ho.getItemByName("long-opt");
		assertNotNull(item);

		assertEquals("file", item.getMeta("long-opt"));
	}

	@Test
	public void testTwoOptions() {
		HelpOptions ho = parseOptions("--option1=file\n    With meta\n\n" +
				"--option2=name\n    Second opt\n");

		HelpOptionItem item = ho.getItemByName("option1");
		assertEquals("With meta", item.getDescription());
		item = ho.getItemByName("option2");
		assertEquals("Second opt", item.getDescription());
	}

	@Test
	public void testInitialDescription() {
		HelpOptions ho = parseOptions("Initial description\n\n" +
				"--option1=name\n    First opt\n");

		HelpOptionItem item = ho.getItemByName("option1");
		assertNotNull(item);
		assertEquals("First opt", item.getDescription());
	}

	@Test
	public void testDescriptionInBetween() {
		HelpOptions ho = parseOptions("Initial description\n\n" +
				"--option1=name\n    First opt\n\n" +
				"New section\n\n" +
				"--option2=file\n    Second opt\n");

		HelpOptionItem option1 = ho.getItemByName("option1");
		assertNotNull(option1);
		assertEquals("First opt", option1.getDescription());

		HelpOptionItem option2 = ho.getItemByName("option2");
		assertNotNull(option2);
		assertEquals("Second opt", option2.getDescription());
	}

	@Test
	public void testWithDefault() {
		HelpOptions ho = parseOptions("Initial description\n\n" +
				"--option1\n    First opt\n    # default:on\n\n" +
				"--option2\n    Second opt\n    # default:off\n\n"
		);

		assertNotNull(ho.getItemByName("option1"));
		assertNotNull(ho.getItemByName("option2"));
	}

	@Test
	public void testDefaultBooleanOn() {
		HelpOptions ho = parseOptions("Initial description\n\n" +
				"--option1\n    First opt\n    # default:on\n\n"
		);

		assertEquals("", ho.getItemByName("option1").getDefault());
	}

	@Test
	public void testTestBooleanBadDefault() {
		HelpOptions ho = parseOptions("--option1\n    First opt\n    # default: 'invalid default for boolean'\n\n");

		assertNull(ho.getItemByName("option1").getDefault());
	}

	@Test
	public void testWithDefaultString() {
		HelpOptions ho = parseOptions("--option1=FILE\n    First opt\n    # default: 'default value'\n\n");
		assertEquals("default value", ho.getItemByName("option1").getDefault());
	}

	@Test
	public void testNoDefault() {
		HelpOptions ho = parseOptions("--option1=FILE\n    First opt\n");
		assertNull(ho.getItemByName("option1").getDefault());
	}

	/**
	 * An option that will be removed, but currently is still available.  There may be a message as
	 * a warning for a replacement.
	 */
	@Test
	public void testOldOption() {
		HelpOptions ho = parseOptions("--option1=FILE # old: no longer needed\n    First opt\n");
		HelpOption opt = ho.getOptionByName("option1");
		assertTrue(opt.isOld());
	}

	/**
	 * A removed option is not usable and raises an error. A message is available for information
	 * about what to do instead if anything.
	 */
	@Test
	public void testRemovedOption() {
		HelpOptions ho = parseOptions("--option1=FILE # removed\n    No longer needed.\n");
		HelpOption opt = ho.getOptionByName("option1");
		assertTrue(opt.isRemoved());
	}

	@Test //@Ignore
	public void testFromFile() throws IOException {
		InputStream stream = new FileInputStream("test/resources/help/test");
		TestUtils.registerFile(stream);
		assertNotNull(stream);

		HelpOptions ho = new HelpOptions();
		ho.parse(stream);

		// Test against the straightforward way of doing this
		Set<String> oldDirect = getValidOptions();
		assertNotNull(oldDirect);
		Set<String> optSet = ho.getOptionNameSet();
		assertNotNull(optSet);

		assertEquals(oldDirect, optSet);
		assertEquals(oldDirect.size(),  optSet.size());
		assertTrue(oldDirect.containsAll(optSet));
		assertTrue(optSet.containsAll(oldDirect));
	}

	/**
	 * Parse the given string into a set of help options.
	 *
	 * @param in The help option fragment to compile.
	 * @return A {@link HelpOptions} object resulting from parsing the string.
	 */
	private HelpOptions parseOptions(String in) {
		InputStream io = null;
		try {
			io = new ByteArrayInputStream(in.getBytes("utf-8"));
		} catch (UnsupportedEncodingException e) {
			// can't really happen as utf-8 support is guaranteed.
		}
		HelpOptions ho = new HelpOptions();
		ho.parse(io);
		return ho;
	}

	/**
	 * Uses the old original way of finding the option names to run as a counter check.
	 */
	private static Set<String> getValidOptions() throws FileNotFoundException {
		String path = "test/resources/help/test";
		InputStream stream = new FileInputStream(path);

		Set<String> result = new HashSet<String>();
		try {
			BufferedReader r = new BufferedReader(new InputStreamReader(stream, "utf-8"));

			Pattern p = Pattern.compile("^--?([a-zA-Z0-9-]*).*$");
			String line;
			while ((line = r.readLine()) != null) {
				Matcher matcher = p.matcher(line);
				if (matcher.matches()) {
					String opt = matcher.group(1);
					result.add(opt);
				}
			}
		} catch (IOException e) {
			System.err.println("Could not read valid optoins");
			return null;
		}

		return result;
	}
}
