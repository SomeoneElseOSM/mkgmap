/*
 * Copyright (C) 2006 Steve Ratcliffe
 * 
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 * 
 * Author: Steve Ratcliffe
 * Create date: 16-Dec-2006
 */
package uk.me.parabola.mkgmap.reader.polish;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;


import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import uk.me.parabola.imgfmt.FormatException;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.net.AccessTagsAndBits;
import uk.me.parabola.imgfmt.app.net.NumberStyle;
import uk.me.parabola.imgfmt.app.net.Numbers;
import uk.me.parabola.imgfmt.app.trergn.ExtTypeAttributes;
import uk.me.parabola.imgfmt.app.trergn.TREHeader;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.filters.LineSplitterFilter;
import uk.me.parabola.mkgmap.general.CityInfo;
import uk.me.parabola.mkgmap.general.LevelInfo;
import uk.me.parabola.mkgmap.general.LoadableMapDataSource;
import uk.me.parabola.mkgmap.general.MapElement;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.mkgmap.general.MapShape;
import uk.me.parabola.mkgmap.general.ZipCodeInfo;
import uk.me.parabola.mkgmap.reader.MapperBasedMapDataSource;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.GeneralRelation;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation;
import uk.me.parabola.mkgmap.reader.osm.Way;

/**
 * Read an data file in Polish format.  This is the format used by a number
 * of other garmin map making programs notably cGPSmapper.
 * <p>
 * As the input format is designed for garmin maps, it is fairly easy to read
 * into mkgmap.  Not every feature of the format is read yet, but it shouldn't
 * be too difficult to add them in as needed.
 * <p>
 * Now will place elements at the level specified in the file and not at the
 * automatic level that is used in eg. the OSM reader.
 */
public class PolishMapDataSource extends MapperBasedMapDataSource implements LoadableMapDataSource {
	private static final Logger log = Logger.getLogger(PolishMapDataSource.class);

	private static final String READING_CHARSET = "iso-8859-1";

	private static final int S_IMG_ID = 1;
	private static final int S_POINT = 2;
	private static final int S_POLYLINE = 3;
	private static final int S_POLYGON = 4;
    private static final int S_RESTRICTION = 5;

	private MapPoint point;
	private MapLine polyline;
	private MapShape shape;

    private PolishTurnRestriction restriction;

    /** contains the information from the lines starting with DATA */
	private final Map<Integer, List<List<Coord>>> lineStringMap = new LinkedHashMap<>();

	private final RoadHelper roadHelper = new RoadHelper();
    private final RestrictionHelper restrictionHelper = new RestrictionHelper();

	private Map<String, String> extraAttributes;

	private String copyright;
	private int section;
	private LevelInfo[] levels;
	private int endLevel;
	private char elevUnits;
	private int currentLevel;
	private int poiDispFlag;
	private String defaultCountry;
	private String defaultRegion;
	private static final double METERS_TO_FEET = 3.2808399;

	private int lineNo;

	private boolean havePolygon4B;

	// Use to decode labels if they are not in cp1252
	private CharsetDecoder dec;

	Long2ObjectOpenHashMap<Coord> coordMap = new Long2ObjectOpenHashMap<>();
	public boolean isFileSupported(String name) {
		// Supported if the extension is .mp
		return name.endsWith(".mp") || name.endsWith(".MP") || name.endsWith(".mp.gz");
	}

	@Override
	public void load(String name, boolean addBackground) throws FileNotFoundException {
		Reader reader;
		try {
			reader = new InputStreamReader(Utils.openFile(name), READING_CHARSET);
		} catch (UnsupportedEncodingException e) {
			// Java is required to support iso-8859-1 so this is unlikely
			throw new FormatException("Unrecognised charset " + READING_CHARSET);
		}

		// If no code page is given then we read labels in utf-8
		dec = Charset.forName("utf-8").newDecoder();
		dec.onUnmappableCharacter(CodingErrorAction.REPLACE);

        
		try (BufferedReader in = new BufferedReader(reader)){
			String line;
			while ((line = in.readLine()) != null) {
				++lineNo;
				line = line.trim();
				if (line.isEmpty() || line.charAt(0) == ';')
					continue;
				if (line.toUpperCase().startsWith("[END"))
					endSection();
				else if (line.charAt(0) == '[')
					sectionStart(line);
				else
					processLine(line);
			}

            // Add all restrictions to the map after reading the full map.
            // The reason being, the restrictions section appear in the beginning of the map.
            // All the nodes will only be read later on.
            // Required to pass the road helper instance as it contains all node data.
            restrictionHelper.processAndAddRestrictions(roadHelper, mapper);
		} catch (IOException e) {
			throw new FormatException("Reading file failed", e);
		}

		if (addBackground && !havePolygon4B)
			addBackground();
		coordMap = null;
	}

	public LevelInfo[] mapLevels() {
		if (levels == null) {
			// If it has not been set then supply some defaults.
			levels = new LevelInfo[] {
					new LevelInfo(3, 17),
					new LevelInfo(2, 18),
					new LevelInfo(1, 22),
					new LevelInfo(0, 24),
			};
		}
		levels[0].setTop(true);
		return levels;
	}

	
	@Override
	public LevelInfo[] overviewMapLevels() {
		String levelSpec = getConfig().getProperty("overview-levels");
		
		if (levelSpec == null)
			return null;
		LevelInfo[] ovLevels = LevelInfo.createFromString(levelSpec); 
		for (int i = 0; i < ovLevels.length; i++) {
			ovLevels[i] = new LevelInfo(ovLevels.length - i - 1, ovLevels[i].getBits());
		}
		return ovLevels;
	}

	/**
	 * Get the copyright message.  We use whatever was specified inside the
	 * MPF itself.
	 *
	 * @return A string description of the copyright.
	 */
	public String[] copyrightMessages() {
		return new String[] {copyright};
	}

	/**
	 * Record that we are starting a new section.
	 * Section names are enclosed in square brackets.  Inside the section there
	 * are a number of lines with the key=value format.
	 *
	 * @param line The raw line from the input file.
	 */
	private void sectionStart(String line) {
		String name = line.substring(1, line.length() - 1).trim();
		log.debug("section name", name);

		extraAttributes = null;

		if (name.equalsIgnoreCase("IMG ID")) {
			section = S_IMG_ID;
			poiDispFlag = 0;
		} else if (name.equalsIgnoreCase("POI") || name.equals("RGN10") || name.equals("RGN20")) {
			point = new MapPoint();
			section = S_POINT;
		} else if (name.equalsIgnoreCase("POLYLINE") || name.equals("RGN40")) {
			polyline = new MapLine();
			roadHelper.clear();
			section = S_POLYLINE;
		} else if (name.equalsIgnoreCase("POLYGON") || name.equals("RGN80")) {
			shape = new MapShape();
			section = S_POLYGON;
		} else if (name.equalsIgnoreCase("Restrict")) {
            restriction = new PolishTurnRestriction();
            section = S_RESTRICTION;
        }
		else
			System.out.println("Ignoring unrecognised section: " + name);
	}

	/**
	 * At the end of a section, we add what ever element that we have been
	 * building to the map.
	 */
	private void endSection() {
		switch (section) {

		case S_IMG_ID:
			break;

		case S_POINT:
			if(extraAttributes != null && point.hasExtendedType())
				point.setExtTypeAttributes(makeExtTypeAttributes());
			mapper.addToBounds(point.getLocation());
			mapper.addPoint(point);
			break;
		case S_POLYLINE:
			if (!lineStringMap.isEmpty()) {
				MapLine origPolyline = polyline.copy();
				
				for (Map.Entry<Integer , List<List<Coord>>> entry : lineStringMap.entrySet()) {
					int level = entry.getKey();
					setResolution(origPolyline, level);
					for (List<Coord> points : entry.getValue()) {
						polyline = origPolyline.copy();
						if (roadHelper.isRoad() && level == 0) {
							polyline.setPoints(points);
							mapper.addRoad(roadHelper.makeRoad(polyline));
						}
						else {
							if(extraAttributes != null && polyline.hasExtendedType())
								polyline.setExtTypeAttributes(makeExtTypeAttributes());
							final int maxPointsInLine = LineSplitterFilter.MAX_POINTS_IN_LINE;
							if(points.size() > maxPointsInLine) {
								List<Coord> segPoints = new ArrayList<>(maxPointsInLine);
								for(Coord p : points) {
									segPoints.add(p);
									if(segPoints.size() == maxPointsInLine) {
										MapLine seg = polyline.copy();
										seg.setPoints(segPoints);
										mapper.addLine(seg);
										segPoints = new ArrayList<>(maxPointsInLine);
										segPoints.add(p);
									}
								}
								if(!segPoints.isEmpty()) {
									polyline.setPoints(segPoints);
									mapper.addLine(polyline);
								}
							}
							else {
								polyline.setPoints(points);
								mapper.addLine(polyline);
							}
						}
					}
				}
			}
			break;
		case S_POLYGON:
			if (!lineStringMap.isEmpty()) {
				if (extraAttributes != null && shape.hasExtendedType())
					shape.setExtTypeAttributes(makeExtTypeAttributes());
				for (Map.Entry<Integer , List<List<Coord>>> entry : lineStringMap.entrySet()) {
					setResolution(shape, entry.getKey());
					addShapesFromPattern(entry.getValue());
				}
			}
			break;
        case S_RESTRICTION:
            restrictionHelper.addRestriction(restriction);
            break;
		case 0:
			// ignored section
			break;

		default:
			log.warn("unexpected default in switch", section);
			break;
		}

		// Clear the section state.
		section = 0;
		endLevel = 0;
		lineStringMap.clear();
		currentLevel = 0;
	}

	private void addShapesFromPattern(List<List<Coord>> pointsLists) {
		if (pointsLists.size() == 1) {
			MapShape copy = shape.copy();
			copy.setPoints(pointsLists.get(0));
			mapper.addShape(copy);
		} else {
			// we have a multipolygon with multiple rings, use
			// MultiPolygonRelation to compute the geometry
			Map<Long, Way> wayMap = new HashMap<>();
			GeneralRelation gr = new GeneralRelation(FakeIdGenerator.makeFakeId());
			// add one tag so that MultiPolygonRelation doesn't ignore the relation 
			gr.addTag("code", Integer.toHexString(shape.getType())); 
			for (int i = 0; i < pointsLists.size(); i++) {
				Way w = new Way(FakeIdGenerator.makeFakeId(), pointsLists.get(i));
				wayMap.put(w.getId(), w);
				// empty role, let MultiPolygonRelation find out what is inner or outer
				gr.addElement("", w);  
			}
			MultiPolygonRelation mp = new MultiPolygonRelation(gr, wayMap, Area.PLANET);
			mp.processElements();
			for (Way s: wayMap.values()) {
				if (MultiPolygonRelation.STYLE_FILTER_POLYGON.equals(s.getTag(MultiPolygonRelation.STYLE_FILTER_TAG))) {
					MapShape copy = shape.copy();
					copy.setPoints(s.getPoints());
					mapper.addShape(copy);
				}
			}
		}
		
	}

	/**
	 * This should be a line that is a key value pair.  We switch out to a
	 * routine that is dependent on the section that we are in.
	 *
	 * @param line The raw input line from the file.
	 */
	private void processLine(String line) {
		String[] nameVal = line.split("=", 2);
		if (nameVal.length != 2) {
			log.warn("short line? " + line);
			return;
		}
		String name = nameVal[0].trim();
		String value = nameVal[1].trim();

		log.debug("LINE: ", name, "|", value);
		
		switch (section) {
		case S_IMG_ID:
			imgId(name, value);
			break;
		case S_POINT:
			if (!isCommonValue(point, name, value))
				point(name, value);
			break;
		case S_POLYLINE:
			if (!isCommonValue(polyline, name, value))
				line(name, value);
			break;
		case S_POLYGON:
			if (!isCommonValue(shape, name, value))
				shape(name, value);
			break;
        case S_RESTRICTION:
            restriction(name, value);
            break;
		default:
			log.debug("line ignored");
			break;
		}
	}


	/**
	 * This is called for every line within the POI section.  The lines are
	 * key value pairs that have already been decoded into name and value.
	 * For each name we recognise we set the appropriate property on
	 * the <i>point</i>.
	 *
	 * @param name Parameter name.
	 * @param value Its value.
	 */
	private void point(String name, String value) {
		if (name.equals("Type")) {
			int type = Integer.decode(value);
			point.setType(type);
		} else if (name.equals("SubType")) {
			int subtype = Integer.decode(value);
			int type = point.getType();
			if (type <= 0xff)
				point.setType((type << 8) | subtype);
		} else if (name.startsWith("Data") || name.startsWith("Origin")) {
			Coord co = makeCoord(value);
			setResolution(point, name);
			point.setLocation(co);
		}
		else {
			if(extraAttributes == null)
				extraAttributes = new HashMap<>();
			extraAttributes.put(name, value);
		}
	}

	/**
	 * Called for each command in a POLYLINE section.  There will be a Data
	 * line consisting of a number of co-ordinates that must be separated out
	 * into points.
	 *
	 * @param name Command name.
	 * @param value Command value.
	 * @see #point
	 */
	private void line(String name, String value) {
		if (name.equals("Type")) {
			polyline.setType(Integer.decode(value));
		} else if (name.startsWith("Data")) {
			extractResolution(name);
			addLineString(value, false);
			// If it is a contour line, then fix the elevation if required.
			if ((polyline.getType() == 0x20) ||
			    (polyline.getType() == 0x21) ||
			    (polyline.getType() == 0x22)) {
				fixElevation();
			}
		} else if (name.equals("RoadID")) {
			roadHelper.setRoadId(Integer.parseInt(value));
		} else if (name.startsWith("Nod")) {
			roadHelper.addNode(value);
		} else if (name.equals("RouteParam") || name.equals("RouteParams")) {
			roadHelper.setParam(value);
		} else if (name.equals("DirIndicator")) {
			polyline.setDirection(Integer.parseInt(value) > 0);
		} else if (name.startsWith("Numbers")) {
			roadHelper.addNumbers(parseNumbers(value));
		} else {
			if (extraAttributes == null)
				extraAttributes = new HashMap<>();
			extraAttributes.put(name, value);
		}
	}

	/**
	 * This constructor takes a comma separated list as in the polish format. Also used in testing as
	 * it is an easy way to set all common parameters at once.
	 *
	 * @param spec Node number, followed by left and then right parameters as in the polish format.
	 */
	public Numbers parseNumbers(String spec) {
		Numbers nums = new Numbers();
		String[] strings = spec.split(",");
		nums.setNodeNumber(Integer.parseInt(strings[0]));
		NumberStyle numberStyle = NumberStyle.fromChar(strings[1]);
		int start = Integer.parseInt(strings[2]);
		int end = Integer.parseInt(strings[3]);
		nums.setNumbers(Numbers.LEFT, numberStyle, start, end);
		numberStyle = NumberStyle.fromChar(strings[4]);
		start = Integer.parseInt(strings[5]);
		end = Integer.parseInt(strings[6]);
		nums.setNumbers(Numbers.RIGHT, numberStyle, start, end);

		if (strings.length > 8){
			// zip codes 
			String zip = strings[7];
			if (!"-1".equals(zip))
				nums.setZipCode(Numbers.LEFT, new ZipCodeInfo(zip));
			zip = strings[8];
			if (!"-1".equals(zip))
				nums.setZipCode(Numbers.RIGHT, new ZipCodeInfo(zip));
		}
		if (strings.length > 9){
			String city,region,country;
			int nextPos = 9;
			city = strings[nextPos];
			if (!"-1".equals(city)){
				region = strings[nextPos + 1];
				country = strings[nextPos + 2];
				nums.setCityInfo(Numbers.LEFT, createCityInfo(city, region, country));
				nextPos = 12;
			} else 
				nextPos = 10;
			city = strings[nextPos];
			if ("-1".equals(city)){
				region = strings[nextPos + 1];
				country = strings[nextPos + 2];
				nums.setCityInfo(Numbers.RIGHT, createCityInfo(city, region, country));
			} 			
		}
		return nums;
	}
	
	private CityInfo createCityInfo(String city, String region, String country) {
		return new CityInfo(recode(city), recode(region), unescape(recode(country)));
	}
	
	private List<Coord> coordsFromString(String value, boolean close) {
		String[] ords = value.split("\\) *, *\\(");
		List<Coord> points = new ArrayList<>();

		for (String s : ords) {
			Coord co = makeCoord(s);
			if (log.isDebugEnabled())
				log.debug(" L: ", co);
			mapper.addToBounds(co);
			points.add(co);
		}
		if (close && points.get(0) != points.get(points.size() - 1)) {
			// not closed, close it
			points.add(points.get(0));
		}
		log.debug(points.size() + " points from " + value);
		return points;
	}

	/**
	 * The elevation needs to be in feet.  So if it is given in meters then
	 * convert it.
	 */
	private void fixElevation() {
		if (elevUnits == 'm') {
			String h = polyline.getName();
			try {
				// Convert to feet.
				int n = Integer.parseInt(h);
				n *= METERS_TO_FEET;
				polyline.setName(String.valueOf(n));

			} catch (NumberFormatException e) {
				// OK it wasn't a number, leave it alone
			}
		}
	}

	/**
	 * Called for each command in a POLYGON section.  There will be a Data
	 * line consisting of a number of co-ordinates that must be separated out
	 * into points.
	 *
	 * @param name Command name.
	 * @param value Command value.
	 * @see #line
	 */
	private void shape(String name, String value) {
		if (name.equals("Type")) {
			int type = Integer.decode(value);
			if (type == 0x4a00)
				type = 0x4a;
			shape.setType(type);
			if(type == 0x4b)
				havePolygon4B = true;
		} else if (name.startsWith("Data")) {
			extractResolution(name);
			addLineString(value, true);
		}
		else {
			if(extraAttributes == null)
				extraAttributes = new HashMap<>();
			extraAttributes.put(name, value);
		}
	}

	private void addLineString (String value, boolean close) {
		List<List<Coord>> lists = lineStringMap.get(currentLevel);
		if (lists == null) {
			lists = new ArrayList<>();
			lineStringMap.put(currentLevel, lists);
		}
		lists.add(coordsFromString(value, close));
	}
	
	private boolean isCommonValue(MapElement elem, String name, String value) {
		if (name.equals("Label")) {
			elem.setName(unescape(recode(value)));
		} else if (name.equals("Label2") || name.equals("Label3")) {
			elem.add2Name(unescape(recode(value)));
		} else if (name.equals("Levels") || name.equals("EndLevel") || name.equals("LevelsNumber")) {
			try {
				endLevel = Integer.valueOf(value);
			} catch (NumberFormatException e) {
				endLevel = 0;
			}
		} else if (name.equals("ZipCode")) {
		  elem.setZip(recode(value));
		} else if (name.equals("CityName")) {
		  elem.setCity(recode(value));		  
		} else if (name.equals("StreetDesc")) {
		  elem.setStreet(recode(value));
		} else if (name.equals("HouseNumber")) {
		  elem.setHouseNumber(recode(value));
		} else if (name.equals("is_in")) {
		  elem.setIsIn(recode(value));		  
		} else if (name.equals("Phone")) {
		  elem.setPhone(recode(value));			
		} else if (name.equals("CountryName")) {
		  elem.setCountry(unescape(recode(value)));
		} else if (name.equals("RegionName")) {
		  elem.setRegion(recode(value));				
		} else {
			return false;
		}

		// We dealt with it
		return true;
	}

	/**
	 * Deal with the polish map escape codes of the form ~[0x##].  These
	 * stand for a single character and is usually used for highway
	 * symbols, name separators etc.
	 *
	 * The code ~[0x05] stands for the character \005 for example.
	 * 
	 * @param s The original string that may contain codes.
	 * @return A string with the escape codes replaced by the single character.
	 */
	public static String unescape(String s) {
		int ind = s.indexOf("~[");
		if (ind < 0)
			return s;

		StringBuilder sb = new StringBuilder();
		if (ind > 0)
			sb.append(s.substring(0, ind));

		char[] buf = s.toCharArray();
		while (ind < buf.length) {
			if (ind < buf.length-2 && buf[ind] == '~' && buf[ind+1] == '[') {
				StringBuilder num = new StringBuilder();
				ind += 2; // skip "~["
				while (ind < buf.length && buf[ind++] != ']')
					num.append(buf[ind - 1]);

				try {
					int inum = Integer.decode(num.toString());

					// Convert any that are in 6-bit format
					if (inum == 0x1b2c) inum = 0x1c;
					if (inum >= 0x2a)
						inum -= 0x29;
					sb.append((char) inum);
				} catch (NumberFormatException e) {
					// Input is malformed so lets just ignore it.
				}
			} else {
				sb.append(buf[ind]);
				ind++;
			}
		}
		return sb.toString();
	}

	/**
	 * Convert the value of a label into a string based on the declared
	 * code page in the file.
	 *
	 * This makes assumptions about the way that the .mp file is written
	 * that may not be correct.
	 *
	 * @param value The string that has been read with ISO-8859-1.
	 * @return A possibly different string that is obtained by taking the
	 * bytes in the input string and decoding them as if they had the
	 * declared code page.
	 */
	private String recode(String value) {
		if (dec != null) {
			try {
				// Get the bytes that were actually in the file.
				byte[] bytes = value.getBytes(READING_CHARSET);
				ByteBuffer buf = ByteBuffer.wrap(bytes);

				// Decode from bytes with the correct code page.
				CharBuffer out = dec.decode(buf);
				return out.toString();
			} catch (UnsupportedEncodingException e) {
				// Java requires this support, so unlikely to happen
				log.warn("no support for " + READING_CHARSET);
			} catch (CharacterCodingException e) {
				log.error("error decoding label", e);
			}
		}
		return value;
	}

	private void setResolution(MapElement elem, String name) {
		
		if (endLevel > 0) {
			elem.setMinResolution(extractResolution(endLevel));
		    elem.setMaxResolution(extractResolution(name));
		} else {
			int res = extractResolution(name);
			elem.setMinResolution(res);
			elem.setMaxResolution(res);
		}
	}

	private void setResolution(MapElement elem, int level) {
		if (endLevel > 0) {
		    elem.setMaxResolution(extractResolution(level));
			if (lineStringMap.size() > 1) {
				for (int i = level+1; i < endLevel; i++) {
					if (lineStringMap.containsKey(i)) {
						elem.setMinResolution(extractResolution(i-1));
						return;
					}
				}
			}
			elem.setMinResolution(extractResolution(endLevel));
		} else {
			int res = extractResolution(level);
			elem.setMinResolution(res);
			elem.setMaxResolution(res);
		}
	}

	/**
	 * Extract the resolution from the Data label.  The name will be something
	 * like Data2: from that we know it is at level 2 and we can look up
	 * the resolution.
	 *
	 * @param name The name tag DataN, where N is a digit corresponding to the
	 * level.
	 *
	 * @return The resolution that corresponds to the level.
	 */
	private int extractResolution(String name) {
		currentLevel = Integer.parseInt(name.substring(name.charAt(0) == 'O'? 6: 4));
		return extractResolution(currentLevel);
	}

	/**
	 * Extract resolution from the level.
	 *
	 * @param level The level (0..)
	 * @return The resolution.
	 * @see #extractResolution(String name)
	 */
	private int extractResolution(int level) {
		int nlevels = levels.length;

		// Some maps use EndLevel=9 to mean the highest level
		if (level >= nlevels)
			level = nlevels - 1;

		LevelInfo li = levels[nlevels - level - 1];
		return li.getBits();
	}


	/**
	 * The initial 'IMG ID' section.  Contains miscellaneous parameters for
	 * the map.
	 *
	 * @param name Command name.
	 * @param value Command value.
	 */
	private void imgId(String name, String value) {
		if (name.equals("Copyright")) {
			copyright = value;
		} else if (name.equals("Levels")) {
			int nlev = Integer.parseInt(value);
			levels = new LevelInfo[nlev];
		} else if (name.startsWith("Level")) {
			int level = Integer.parseInt(name.substring(5), 10);
			int bits = Integer.parseInt(value);
			LevelInfo info = new LevelInfo(level, bits);

			int nlevels = levels.length;
			if (level >= nlevels)
				return;

			levels[nlevels - level - 1] = info;
		} else if (name.startsWith("Elevation")) {
			char fc = value.charAt(0);
			if (fc == 'm' || fc == 'M')
				elevUnits = 'm';
		} else if (name.equalsIgnoreCase("CodePage")) {
			dec = Charset.forName("cp" + value).newDecoder();
			dec.onUnmappableCharacter(CodingErrorAction.REPLACE);
		} else if (name.endsWith("LeftSideTraffic")){
			if ("Y".equals(value)){
				setDriveOnLeft(true);
			} else if ("N".equals(value)){ 
				setDriveOnLeft(false);
			}
		} else if ("Transparent".equals(name)) {
			if ("Y".equals(value) || "S".equals(value))
				poiDispFlag |= TREHeader.POI_FLAG_TRANSPARENT; 
		} else if ("POIZipFirst".equals(name)) {
			if ("Y".equals(value))
				poiDispFlag |= TREHeader.POI_FLAG_POSTALCODE_BEFORE_CITY; 
		} else if ("POINumberFirst".equals(name)) {
			if ("N".equals(value))
				poiDispFlag |= TREHeader.POI_FLAG_STREET_BEFORE_HOUSENUMBER;  
		} else if ("Numbering".equals(name)) {
			// ignore
		} else if ("Routing".equals(name)) {
			// ignore
		} else if ("CountryName".equalsIgnoreCase(name)) {
			defaultCountry = value;
		} else if ("RegionName".equalsIgnoreCase(name)) {
			defaultRegion = value;
		} else {
			System.out.println("'IMG ID' section: ignoring " + name + " " + value);
		}
		
	}

	/**
	 * Create a coordinate from a string.  The string will look similar:
	 * (2.3454,-0.23), but may not have the leading opening parenthesis.
	 * @param value A string representing a lat,long pair.
	 * @return The coordinate value.
	 */
	private Coord makeCoord(String value) {
		String[] fields = value.split("[(,)]");

		int i = 0;
		if (fields[0].isEmpty())
			i = 1;

		Double f1 = Double.valueOf(fields[i]);
		Double f2 = Double.valueOf(fields[i+1]);
		Coord co = new Coord(f1, f2);
		long key = Utils.coord2Long(co);
		Coord co2 = coordMap.get(key);
		if (co2 != null)
			return co2;
		coordMap.put(key, co);
		return co;
	}

	private ExtTypeAttributes makeExtTypeAttributes() {
		Map<String, String> eta = new HashMap<>();
		int colour = 0;
		int style = 0;

		for(Map.Entry<String, String> entry : extraAttributes.entrySet()) {
			String v = entry.getValue();
			if (entry.getKey().equals("Depth")) {
				String u = extraAttributes.get("DepthUnit");
				if("f".equals(u))
					v += "ft";
				eta.put("depth", v);
			} else if(entry.getKey().equals("Height")) {
				String u = extraAttributes.get("HeightUnit");
				if("f".equals(u))
					v += "ft";
				eta.put("height", v);
			} else if(entry.getKey().equals("HeightAboveFoundation")) {
				String u = extraAttributes.get("HeightAboveFoundationUnit");
				if("f".equals(u))
					v += "ft";
				eta.put("height-above-foundation", v);
			} else if(entry.getKey().equals("HeightAboveDatum")) {
				String u = extraAttributes.get("HeightAboveDatumUnit");
				if("f".equals(u))
					v += "ft";
				eta.put("height-above-datum", v);
			} else if(entry.getKey().equals("Color")) {
				colour = Integer.decode(v);
			} else if(entry.getKey().equals("Style")) {
				style = Integer.decode(v);
			} else if(entry.getKey().equals("Position")) {
				eta.put("position", v);
			} else if(entry.getKey().equals("FoundationColor")) {
				eta.put("color", v);
			} else if(entry.getKey().equals("Light")) {
				eta.put("light", v);
			} else if(entry.getKey().equals("LightType")) {
				eta.put("type", v);
			} else if(entry.getKey().equals("Period")) {
				eta.put("period", v);
			} else if(entry.getKey().equals("Note")) {
				eta.put("note", v);
			} else if(entry.getKey().equals("LocalDesignator")) {
				eta.put("local-desig", v);
			} else if(entry.getKey().equals("InternationalDesignator")) {
				eta.put("int-desig", v);
			} else if(entry.getKey().equals("FacilityPoint")) {
				eta.put("facilities", v);
			} else if(entry.getKey().equals("Racon")) {
				eta.put("racon", v);
			} else if(entry.getKey().equals("LeadingAngle")) {
				eta.put("leading-angle", v);
			}
		}

		if(colour != 0 || style != 0)
			eta.put("style", "0x" + Integer.toHexString((style << 8) | colour));

		return new ExtTypeAttributes(eta, "Line " + lineNo);
	}

    /**
     * Construct the restrictions object.
     */
    private void restriction(String name, String value) {
        try {
            // Proceed only if the restriction is not already marked as invalid.
            if (restriction.isValid()) {
                if (name.equalsIgnoreCase("Nod")) {
                    /* ignore */
                } else if (name.equalsIgnoreCase("TraffPoints")) {
                	restriction.setTrafficPoints(value);
                } else if (name.equalsIgnoreCase("TraffRoads")) {
                	restriction.setTrafficRoads(value);
                } else if (name.equalsIgnoreCase("RestrParam")) {
                    restriction.setExceptMask(getRestrictionExceptionMask(value));
                } else if (name.equalsIgnoreCase("Time")) {
                    // Do nothing for now
                }
            }
        } catch (NumberFormatException ex) { // This exception means that this restriction is not properly defined.
            restriction.setValid(false); // Mark this as an invalid restriction.
            log.error("Invalid restriction definition. " + restriction);
        }
    }

    /**
     * Constructs the vehicle exception mask from the restriction params.
     * From cGPSMapper manual :-
     * <p>
     * By default restrictions apply to all kind of vehicles, if
     * RestrParam is used, then restriction will be ignored by
     * specified types of vehicles.
     * </p>
     * <p>
     * [Emergency],[delivery],[car],[bus],[taxi],[pedestrian],[bicycle],[truck]
     * </p>
     * <p>
     * Example:
     * RestrParam=0,1,1,0
     * </p>
     * Above definition will set the restriction to be applied for
     * Emergency, Bus, Taxi, Pedestrian and Bicycle. Restriction
     * will NOT apply for Delivery and Car.
     *
     * @param value Tag value
     * @return the exceptMask in mkgmap internal format
     */
    private static byte getRestrictionExceptionMask(String value) {
        String[] params = value.split(",");
        byte exceptMask = 0x00;
        if (params.length > 0 && params.length <= 8) { // Got to have at least one parameter but not more than 8.
			for (int i = 0; i < params.length; i++) {
                if ("1".equals(params[i])) {
                    switch(i) {
					case 0:
						exceptMask |= AccessTagsAndBits.EMERGENCY;
						break;
					case 1:
						exceptMask |= AccessTagsAndBits.DELIVERY;
						break;
					case 2:
						exceptMask |= AccessTagsAndBits.CAR;
						break;
					case 3:
						exceptMask |= AccessTagsAndBits.BUS;
						break;
					case 4:
						exceptMask |= AccessTagsAndBits.TAXI;
						break;
					case 5:
						exceptMask |= AccessTagsAndBits.FOOT;
						break;
					case 6:
						exceptMask |= AccessTagsAndBits.BIKE;
						break;
					case 7:
						exceptMask |= AccessTagsAndBits.TRUCK;
						break;
                    }
                }
            }
        } else {
            log.error("Invalid RestrParam definition. -> " + value);
        }

        return exceptMask;
    }

	@Override
	public int getPoiDispFlag() {
		return poiDispFlag;
	}

	public String getDefaultCountry() {
		return defaultCountry;
	}

	public String getDefaultRegion() {
		return defaultRegion;
	}
}
