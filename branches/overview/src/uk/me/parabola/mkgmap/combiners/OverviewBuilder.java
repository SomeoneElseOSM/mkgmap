/*
 * Copyright (C) 2010.
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
package uk.me.parabola.mkgmap.combiners;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

import uk.me.parabola.imgfmt.ExitException;
import uk.me.parabola.imgfmt.FileExistsException;
import uk.me.parabola.imgfmt.FileNotWritableException;
import uk.me.parabola.imgfmt.FileSystemParam;
import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.map.Map;
import uk.me.parabola.imgfmt.app.map.MapReader;
import uk.me.parabola.imgfmt.app.trergn.Polygon;
import uk.me.parabola.imgfmt.app.trergn.Polyline;
import uk.me.parabola.imgfmt.app.trergn.Zoom;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.CommandArgs;
import uk.me.parabola.mkgmap.build.MapBuilder;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapShape;

/**
 * Build the overview map.  This is a low resolution map that covers the whole
 * of a map set.  It also contains polygons that correspond to the areas
 * covered by the individual map tiles.
 *
 * @author Steve Ratcliffe
 */
public class OverviewBuilder implements Combiner {
	Logger log = Logger.getLogger(OverviewBuilder.class);

	private final OverviewMap overviewSource;
	private String areaName;
	private String overviewMapname;
	private String overviewMapnumber;
	private Zoom[] levels;

	public OverviewBuilder(OverviewMap overviewSource) {
		this.overviewSource = overviewSource;
	}

	public void init(CommandArgs args) {
		areaName = args.get("area-name", "Overview Map");
		overviewMapname = args.get("overview-mapname", "osmmap");
		overviewMapnumber = args.get("overview-mapnumber", "63240000");
	}

	public void onMapEnd(FileInfo finfo) {
		try {
			readFileIntoOverview(finfo);
		} catch (FileNotFoundException e) {
			throw new MapFailedException("Could not read detail map " + finfo.getFilename(), e);
		}
	}

	public void onFinish() {
		writeOverviewMap();
	}

	/**
	 * Write out the overview map.
	 */
	private void writeOverviewMap() {
		MapBuilder mb = new MapBuilder();
		mb.setEnableLineCleanFilters(false);

		FileSystemParam params = new FileSystemParam();
		params.setBlockSize(512);
		params.setMapDescription(areaName);

		try {
			Map map = Map.createMap(overviewMapname, params, overviewMapnumber);
			mb.makeMap(map, overviewSource);
			map.close();
		} catch (FileExistsException e) {
			throw new ExitException("Could not create overview map", e);
		} catch (FileNotWritableException e) {
			throw new ExitException("Could not write to overview map", e);
		}
	}

	/**
	 * Add an individual .img file to the overview map.
	 *
	 * @param finfo Information about an individual map.
	 */
	private void readFileIntoOverview(FileInfo finfo) throws FileNotFoundException {
		addMapCoverageArea(finfo);

		MapReader mapReader = new MapReader(finfo.getFilename());

		levels = mapReader.getLevels();

		readLines(mapReader);
		readShapes(mapReader);
	}

	/**
	 * Read the lines from the .img file and add them to the overview map.
	 * We read from the least detailed level (apart from the empty one).
	 *
	 * TODO: further filter what is seen
	 *
	 * @param mapReader Map reader on the detailed .img file.
	 */
	private void readLines(MapReader mapReader) {
		int min = levels[1].getLevel();
		List<Polyline> lineList = mapReader.linesForLevel(min);
		for (Polyline line : lineList) {
			log.debug("got line", line);
			MapLine ml = new MapLine();

			List<Coord> list = line.getPoints();
			log.debug("line point list", list);
			if (list.size() < 2)
				continue;

			ml.setType(line.getType());
			ml.setName(line.getLabel().getText());
			ml.setMaxResolution(24); // TODO
			ml.setMinResolution(5);  // TODO
			ml.setPoints(list);

			overviewSource.addLine(ml);
		}
	}

	private void readShapes(MapReader mapReader) {
		int min = levels[1].getLevel();
		List<Polygon> list = mapReader.shapesForLevel(min);
		for (Polygon shape : list) {
			MapShape ms = new MapShape();

			List<Coord> points = shape.getPoints();
			if (points.size() < 2)
				continue;

			ms.setType(shape.getType());
			ms.setName(shape.getLabel().getText());
			ms.setMaxResolution(24); // TODO
			ms.setMinResolution(5);  // TODO
			ms.setPoints(points);

			overviewSource.addShape(ms);
		}
	}

	/**
	 * Add an area that shows the area covered by a detailed map.  This can
	 * be an arbitary shape, although at the current time we only support
	 * rectangles.
	 *
	 * @param finfo Information about a detail map.
	 */
	private void addMapCoverageArea(FileInfo finfo) {
		Area bounds = finfo.getBounds();

		int overviewMask = ((1 << overviewSource.getShift()) - 1);

		int maxLon = roundDown(bounds.getMaxLong(), overviewMask);
		int maxLat = roundUp(bounds.getMaxLat(), overviewMask);
		int minLat = roundUp(bounds.getMinLat(), overviewMask);
		int minLon = roundDown(bounds.getMinLong(), overviewMask);

		// Add a background polygon for this map.
		List<Coord> points = new ArrayList<Coord>();

		Coord[] bgcoords = {new Coord(minLat, minLon), new Coord(maxLat, minLon),
				new Coord(maxLat, maxLon), new Coord(minLat, maxLon)};
		for (Coord co : bgcoords) {
			points.add(co);
			overviewSource.addToBounds(co);
		}

		// join back to the first point.
		points.add(bgcoords[0]);

		// Create the background rectangle
		MapShape bg = new MapShape();
		bg.setType(0x4a);
		bg.setPoints(points);
		bg.setMinResolution(10);
		bg.setName(finfo.getDescription() + '\u001d' + finfo.getMapname());

		overviewSource.addShape(bg);
	}

	private int roundUp(int len, int overviewMask) {
		//System.out.printf("before up 0x%x\n", len);
		if (len > 0)
			return (len + overviewMask) & ~overviewMask;
		else
			return len & ~overviewMask;
	}

	private int roundDown(int len, int overviewMask) {
		//System.out.printf("before down 0x%x\n", len);
		if (len > 0)
			return len & ~overviewMask;
		else
			return -(-len +overviewMask & ~overviewMask);
	}

	public Area getBounds() {
		return overviewSource.getBounds();
	}
}
