/*
 * Copyright (C) 2018.
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

package uk.me.parabola.mkgmap.reader.osm;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.reader.osm.MultiPolygonRelation.JoinedWay;
import uk.me.parabola.util.GpxCreator;
import uk.me.parabola.util.Java2DConverter;
import uk.me.parabola.util.ShapeSplitter;

/**
 * Methods to cut an MP-relation so that holes are connected with the outer way(s).
 * This cutter avoids to use the complex methods in java.awt.geom.area, it simply connects rings by 
 * adding the points of one ring at the right position between two points of the other ring. 
 * 
 * @author Gerd Petermann
 *
 */
public class MultiPolygonCutter2 {
	private static final Logger log = Logger.getLogger(MultiPolygonCutter2.class);
	private final MultiPolygonRelation rel;
	private final Rectangle2D tileBBox;
	private uk.me.parabola.imgfmt.app.Area tileBounds;
	private static final boolean debugGpx = false;

	/**
	 * Create cutter for a given MP-relation and tile
	 * @param multiPolygonRelation the MP-relation
	 * @param tileArea the java area of the tile
	 */
	public MultiPolygonCutter2(MultiPolygonRelation multiPolygonRelation, uk.me.parabola.imgfmt.app.Area tileBounds) {
		rel = multiPolygonRelation;
		this.tileBounds = tileBounds;
		this.tileBBox = new Rectangle2D.Double(tileBounds.getMinLong(), tileBounds.getMinLat(), tileBounds.getWidth(), tileBounds.getHeight());
	}

	/**
	 * Cut out all inner polygons from the outer polygon. This will divide the outer
	 * polygon in several polygons.
	 * @param multiPolygonRelation 
	 * 
	 * @param outerPolygon
	 *            the outer polygon
	 * @param innerPolygons
	 *            a list of inner polygons
	 * @return a list of polygons that make the outer polygon cut by the inner
	 *         polygons
	 */
	public List<Way> cutOutInnerPolygons(JoinedWay outerPolygon, List<JoinedWay> innerPolygons) {
		Long2ObjectOpenHashMap<Coord> coordPool = new Long2ObjectOpenHashMap<>();

		if (innerPolygons.isEmpty()) {
			Way outerWay = new JoinedWay(outerPolygon);
			if (log.isDebugEnabled()) {
				log.debug("Way", outerPolygon.getId(), "splitted to way", outerWay.getId());
			}
			return Collections.singletonList(outerWay);
		}

		// use the java.awt.geom.Area class because it's a quick
		// implementation of what's needed

		// this list contains all non overlapping and singular areas
		// of the outerPolygon
		Queue<AreaCutData> areasToCut = new LinkedList<>();
		Collection<JoinedWay> finishedAreas = new ArrayList<>(innerPolygons.size());
		
		// create a list of Area objects from the outerPolygon (clipped to the bounding box)
		List<JoinedWay> outerAreas = createAreas(outerPolygon, true);
		
		// create the inner areas
		List<JoinedWay> innerAreas = new ArrayList<>();
		for (JoinedWay inner : innerPolygons)  {
			
			List<JoinedWay> clippedInners = createAreas(inner, true);
			
			for (JoinedWay clipped : clippedInners) {
				if (!divideOuters(outerAreas, clipped)) 
					innerAreas.add(clipped);
				
			}
		}
		makeCounterClockWise(outerAreas); // TODO: needed?

		// initialize the cut data queue
		if (innerAreas.isEmpty()) {
			// this is a multipolygon without any inner areas
			// nothing to cut
			finishedAreas.addAll(outerAreas);
		} else if (outerAreas.size() == 1) {
			// there is one outer area only
			// it is checked before that all inner areas are inside this outer area
			AreaCutData initialCutData = new AreaCutData();
			initialCutData.outerArea = outerAreas.get(0);
			initialCutData.innerAreas = innerAreas;
			areasToCut.add(initialCutData);
		} else {
			// multiple outer areas
			for (JoinedWay outerArea : outerAreas) {
				AreaCutData initialCutData = new AreaCutData();
				initialCutData.outerArea = outerArea;
				initialCutData.innerAreas = new ArrayList<>(innerAreas.size());
				for (JoinedWay innerArea : innerAreas) {
					if (outerArea.getBounds2D().intersects(innerArea.getBounds2D())) {
						initialCutData.innerAreas.add(innerArea);
					}
				}
				
				if (initialCutData.innerAreas.isEmpty()) {
					// this is either an error
					// or the outer area has been cut into pieces on the tile bounds
					finishedAreas.add(outerArea);
				} else {
					areasToCut.add(initialCutData);
				}
			}
		}

		int countPoll = 0;
		while (!areasToCut.isEmpty()) {
			AreaCutData areaCutData = areasToCut.poll();
			countPoll++;
			CutPoint cutPoint = calcNextCutPoint(areaCutData);
//			log.error(countPoll, "cutting at",cutPoint,areaCutData.outerArea.getBounds2D());
			if (cutPoint == null) {
				finishedAreas.add(areaCutData.outerArea);
				continue;
			}
			// work around: cutPoint may not contain all areas that intersect with the cut line described by it
			int dividingLine = cutPoint.getCutPointHighPrec();
			List<JoinedWay> allMatchingInner = new ArrayList<>();
			for (JoinedWay inner : areaCutData.innerAreas) {
				if (cutPoint.axis.getStartHighPrec(inner) <= dividingLine && cutPoint.axis.getStopHighPrec(inner) >= dividingLine) {
					allMatchingInner.add(inner);
				}
			}
			if (allMatchingInner.size() != cutPoint.getNumberOfAreas()) {
				drawGpx("outer", areaCutData.outerArea.getPoints());
				drawGpx("c", cutPoint.getCutLine());
				for (int i = 0; i < allMatchingInner.size(); i++) {
					drawGpx("mi"+i, allMatchingInner.get(i).getPoints());
				}
				cutPoint.getAreas().clear();
				cutPoint.getAreas().addAll(allMatchingInner);
			}
			assert cutPoint.getNumberOfAreas() > 0 : "Number of cut areas == 0 in mp " + rel.getId();
//			cutPoint.checkInnerTouchingOuter(areaCutData.outerArea, coordPool);
			List<JoinedWay> splitOuterWays = cutPoint.split(areaCutData.outerArea, coordPool, countPoll);
			areaCutData.innerAreas.removeAll(cutPoint.inners);
			if (areaCutData.innerAreas.isEmpty())
				finishedAreas.addAll(splitOuterWays);
			else {
				for (JoinedWay nextOuterArea : splitOuterWays) {
					ArrayList<JoinedWay> nextInnerAreas = null;
					// go through all remaining inner areas and check if they
					// must be further processed with the nextOuterArea 
					for (JoinedWay nonProcessedInner : areaCutData.innerAreas) {
						if (nextOuterArea.getBounds2D().intersects(nonProcessedInner.getBounds2D())) {
							if (nextInnerAreas == null) {
								nextInnerAreas = new ArrayList<>();
							}
							nextInnerAreas.add(nonProcessedInner);
						}
					}
					
					if (nextInnerAreas == null || nextInnerAreas.isEmpty()) {
						finishedAreas.add(nextOuterArea);
					} else {
						AreaCutData outCutData = new AreaCutData();
						outCutData.outerArea = nextOuterArea;
						outCutData.innerAreas= nextInnerAreas;
						areasToCut.add(outCutData);
					}
					
				}
			}
		}
		coordPool.clear();
		List<Way> cuttedOuterPolygon = new ArrayList<>(finishedAreas.size());
		for (JoinedWay area : finishedAreas) {
			
//			List<Coord> points = removeObsoletePoints(area.getPoints());
			List<Coord> points = area.getPoints();
			Way w = new Way(rel.getOriginalId(), points);
			if (w != null) {
				w.setFakeId();
				// make sure that high-prec equal coords are changed to identical coord instances
				// this allows merging in the ShapeMerger
				int n = w.getPoints().size();
				for (int i = 0; i < n; i++){
					Coord p = w.getPoints().get(i);
					long key = Utils.coord2Long(p);
					Coord replacement = coordPool.get(key);
					if (replacement == null)
						coordPool.put(key, p);
					else {
						assert p.highPrecEquals(replacement);
						w.getPoints().set(i, replacement);
					}
				}
				w.copyTags(outerPolygon);
				cuttedOuterPolygon.add(w);
				if (log.isDebugEnabled()) {
					log.debug("Way", outerPolygon.getId(), "splitted to way", w.getId());
				}
			}
		}
		
		return cuttedOuterPolygon;
	}

	/**
	 * Check if an inner way touches the tile bbox. If so, use slow area.subtract() first.
	 * This is unlikely for normal mp, but happens often with precompiled sea. 
	 * @param outerAreas list with outer polygons, modified if needed
	 * @param inner the inner polygon
	 */
	private boolean divideOuters(List<JoinedWay> outerAreas, JoinedWay inner) {
		drawGpx("i", inner.getPoints());

		boolean innerTouchesOuter = inner.getBounds2D().getMaxY() == tileBBox.getMaxY()
				|| inner.getBounds2D().getMaxX() == tileBBox.getMaxX() 
				|| inner.getBounds2D().getY() == tileBBox.getY()
				|| inner.getBounds2D().getX() == tileBBox.getX();

		if (!innerTouchesOuter)
			return false;
		
		Area innerArea = Java2DConverter.createArea(inner.getPoints());
		
		List<JoinedWay> divided = new ArrayList<>();
		Iterator<JoinedWay> iter = outerAreas.iterator();
		while (iter.hasNext()) {
			JoinedWay o = iter.next();
			if (o.getBounds2D().intersects(inner.getBounds2D())) {
				drawGpx("o", o.getPoints());
				Area outer = Java2DConverter.createArea(o.getPoints());
				outer.subtract(innerArea);
				
				List<List<Coord>> shapes = Java2DConverter.areaToShapes(outer);
				iter.remove();
				for (List<Coord> shape : shapes) {
					drawGpx("s"+divided.size(), shape);
					JoinedWay w = new JoinedWay(o, shape);
					divided.add(w);
				}
			}
		}
		outerAreas.addAll(divided);
		return true;
	}

	private void makeCounterClockWise(List<JoinedWay> rings) {
		for (JoinedWay ring : rings) {
			if (Way.clockwise(ring.getPoints()))
				Collections.reverse(ring.getPoints());
		}
	}

	private static CutPoint calcNextCutPoint(AreaCutData areaData) {
		if (areaData.innerAreas == null || areaData.innerAreas.isEmpty()) {
			return null;
		}
		
		Rectangle2D outerBounds = areaData.outerArea.getBounds2D();
		
		if (areaData.innerAreas.size() == 1) {
			// make it short if there is only one inner area
			CutPoint cutPoint1 = new CutPoint(CoordinateAxis.LATITUDE, outerBounds);
			cutPoint1.addArea(areaData.innerAreas.get(0));
			CutPoint cutPoint2 = new CutPoint(CoordinateAxis.LONGITUDE, outerBounds);
			cutPoint2.addArea(areaData.innerAreas.get(0));
			if (cutPoint1.compareTo(cutPoint2) > 0) {
				return cutPoint1;
			} else {
				return cutPoint2;
			}
			
		}
		
		ArrayList<JoinedWay> innerStart = new ArrayList<>(areaData.innerAreas);
		
		ArrayList<CutPoint> bestCutPoints = new ArrayList<>(CoordinateAxis.values().length);
		for (CoordinateAxis axis : CoordinateAxis.values()) {
			CutPoint bestCutPoint = new CutPoint(axis, outerBounds);
			CutPoint currentCutPoint = new CutPoint(axis, outerBounds);

			Collections.sort(innerStart, (axis == CoordinateAxis.LONGITUDE ? COMP_LONG_START: COMP_LAT_START));

			for (JoinedWay anInnerStart : innerStart) {
				currentCutPoint.addArea(anInnerStart);

				if (currentCutPoint.compareTo(bestCutPoint) > 0) {
					bestCutPoint = currentCutPoint.duplicate();
				}
			}
			bestCutPoints.add(bestCutPoint);
		}
		return Collections.max(bestCutPoints);
		
	}

	/**
	 * Create the areas that are enclosed by the way. Usually the result should
	 * only be one area but some ways contain intersecting lines. To handle these
	 * erroneous cases properly the method might return a list of areas.
	 * 
	 * @param w a closed way
	 * @param clipBbox true if the areas should be clipped to the bounding box; false else
	 * @return a list of enclosed ares
	 */
	private List<JoinedWay> createAreas(JoinedWay w, boolean clipBbox) {
		if (clipBbox && !tileBBox.contains(w.getBounds2D())) {
			// the area intersects the bounding box => clip it
			 List<List<Coord>> split = ShapeSplitter.clipToBounds(w.getPoints(), tileBounds, null);
			 List<JoinedWay> clipped = new ArrayList<>();
			 for (List<Coord> points : split) {
				 clipped.add(new JoinedWay(w, points));
			 }
			 return clipped;
		}  
		return Arrays.asList(w);
	}

	private static class AreaCutData {
		JoinedWay outerArea;
		List<JoinedWay> innerAreas;
	}

	private static class SplitRing {
		final List<Coord> ring;
		int min, max;
		private final int dividingLine;
		private final CoordinateAxis axis;
		public final boolean clockwise;
		final int maxDist;

		public SplitRing(List<Coord> points, int dividingLine, CoordinateAxis axis) {
			this.ring = points;
			this.dividingLine = dividingLine;
			this.axis = axis;
			if (points.size() <= 1 || points.get(0) != points.get(points.size()-1)) {
				log.error("not a ring ?", points.size());
			}
			min = Integer.MAX_VALUE;
			max = Integer.MIN_VALUE;

			while (ring.size() > 1) {
				Coord start = ring.get(0);
				Coord next = ring.get(1);
				Coord prevLast = ring.get(ring.size() - 2);
				boolean spike = false;
				if (start.getHighPrecLat() == next.getHighPrecLat()
						&& start.getHighPrecLat() == prevLast.getHighPrecLat()) {
					spike = true;
				}
				if (start.getHighPrecLon() == next.getHighPrecLon()
						&& start.getHighPrecLon() == prevLast.getHighPrecLon()) {
					spike = true;
				}
				if (!spike)
					break;
//				log.error("spike near", start.toDegreeString());
				drawGpx("spike1", ring);
				ring.remove(ring.size() - 1);
				if (next.highPrecEquals(prevLast))
					ring.remove(0);
				else 
					ring.set(0, prevLast); // new closing
				drawGpx("spike2", ring);
				continue;
			}
			if (ring.size() < 2) {
				ring.clear();
			}
			int md = 0;
			for (Coord c : ring) {
				int distToLine = Math.abs((axis == CoordinateAxis.LATITUDE ? c.getHighPrecLat() : c.getHighPrecLon()) - dividingLine);
				if (md < distToLine)
					md = distToLine;
				if (onDividingLine(c)) {
					int pos = axis.getPosOnAxis(c);
					min = Math.min(min, pos);
					max = Math.max(max, pos);
				}
			}
			maxDist = md;

			clockwise = Way.clockwise(ring);
		}

		public boolean onDividingLine(Coord c) {
			return dividingLine == (axis == CoordinateAxis.LONGITUDE ? c.getHighPrecLon() : c.getHighPrecLat()); 
		}

	}
	
	static class SplitRingComparator implements Comparator<SplitRing> {
		@Override
		public int compare(SplitRing o1, SplitRing o2) {
			int d = Integer.compare(o1.min, o2.min);
			if (d != 0)
				return d;
			d = Integer.compare(o1.max, o2.max);
			if (d != 0)
				return d;
			return Integer.compare(o2.maxDist, o1.maxDist); // larger dist first
		}
	}
	
	private static final int CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD = 1<<(11 + Coord.DELTA_SHIFT);
	private static final int CUT_POINT_CLASSIFICATION_BAD_THRESHOLD = 1<< (8 + Coord.DELTA_SHIFT);
	private static class CutPoint implements Comparable<CutPoint>{
		private int startPointHp = Integer.MAX_VALUE; // high precision map units
		private int stopPointHp = Integer.MIN_VALUE;  // high precision map units
		private Integer cutPointHp = null; // high precision map units
		private final LinkedList<JoinedWay> inners;
		private final Comparator<JoinedWay> comparator;
		private final CoordinateAxis axis;
		private Rectangle2D bounds;
		private final Rectangle2D outerBounds;
		private Double minAspectRatio;

		public CutPoint(CoordinateAxis axis, Rectangle2D outerBounds) {
			this.axis = axis;
			this.outerBounds = outerBounds;
			this.inners = new LinkedList<>();
			this.comparator = (axis == CoordinateAxis.LONGITUDE ? COMP_LONG_STOP : COMP_LAT_STOP);
		}

		/**
		 * Split the outer area at the line described by this cut point and subtract the inner areas.
		 * @param outerRing
		 * @param coordPool 
		 * @param countPoll 
		 * @return 
		 * @return List of rings
		 */
		public List<JoinedWay> split(JoinedWay outerRing, Long2ObjectOpenHashMap<Coord> coordPool, int countPoll) {
			List<List<Coord>> lessOuterList = new ArrayList<>(), moreOuterList = new ArrayList<>();
			drawGpx("outer"+countPoll, outerRing.getPoints());
			drawGpx("c"+countPoll, getCutLine());
			List<SplitRing> moreInners = new ArrayList<>(), lessInners = new ArrayList<>();
			for (int i = 0; i < inners.size(); i++) {
				JoinedWay inner = inners.get(i);
				drawGpx("inner"+i, inner.getPoints());
				List<List<Coord>> lessInnerList = new ArrayList<>(), moreInnerList = new ArrayList<>();
				ShapeSplitter.splitShape(inner.getPoints(), getCutPointHighPrec(), axis.useX, lessInnerList, moreInnerList, coordPool);
				for (int j = 0; j < lessInnerList.size(); j++) {
					List<Coord> points = lessInnerList.get(j);
					assert points.size() > 0 && points.get(0) == points.get(points.size()-1);
					drawGpx("iless"+i+"_"+j, points);
					lessInners.add(new SplitRing(points, getCutPointHighPrec(), axis));
				}
				for (int j = 0; j < moreInnerList.size(); j++) {
					List<Coord> points = moreInnerList.get(j);
					assert points.size() > 0 && points.get(0) == points.get(points.size()-1);
					drawGpx("imore"+i+"_"+j, points);
					moreInners.add(new SplitRing(points, getCutPointHighPrec(), axis));
				}
			}
			List<JoinedWay> res = new ArrayList<>();
			ShapeSplitter.splitShape(outerRing.getPoints(), getCutPointHighPrec(), axis.useX, lessOuterList, moreOuterList, coordPool);
			List<SplitRing> lessOuter = new ArrayList<>(), moreOuter = new ArrayList<>();
			for (int i = 0; i < lessOuterList.size(); i++) {
				List<Coord> points = lessOuterList.get(i);
				assert points.size() > 0 && points.get(0) == points.get(points.size()-1);
				lessOuter.add(new SplitRing(points, getCutPointHighPrec(), axis));
				drawGpx("less"+i, points);
			}
			for (int i = 0; i < moreOuterList.size(); i++) {
				List<Coord> points = moreOuterList.get(i);
				assert points.size() > 0 && points.get(0) == points.get(points.size()-1);
				moreOuter.add(new SplitRing(points, getCutPointHighPrec(), axis));
				drawGpx("more"+i, points);
			}
			boolean modified = false;
			modified |= subtractInnerFromOuter(moreOuter, moreInners, true);
			modified |= subtractInnerFromOuter(lessOuter, lessInners, false);
			if (modified) {
				for (SplitRing o : lessOuter) {
					if (!o.ring.isEmpty())
						res.add(new JoinedWay(outerRing, o.ring));
				}
				for (SplitRing o : moreOuter) {
					if (!o.ring.isEmpty())
						res.add(new JoinedWay(outerRing, o.ring));
				}
			} else {
//				log.error("split reverted, inner was not inside outer");
				res.add(outerRing);
			}
			return res;
		}

		/**
		 * An inner ring that was cut at the cutline may touch the cutline multiple times. Extract the parts between as outer
		 * and remove those parts from the inner.
		 
		 * @param moreInners
		 * @param b
		 * @return
		 */
		private List<SplitRing> subtractOuterFromInner(List<SplitRing> inners, boolean isMore) {
			List<SplitRing> res = new ArrayList<>();
			List<SplitRing> newInners = new ArrayList<>();
			
			for (int i = 0; i < inners.size(); i++) {
				SplitRing inner = inners.get(i);
				if (inner.min == inner.max) {
					// inner touches cutline in a single point, cannot enclose an outer ring
					continue;
				}

				int lastHitPos = -1;
				Coord lastHit = null;
				List<SplitRing> splitParts = new ArrayList<>();
				assert inner.onDividingLine(inner.ring.get(0)) : "inner ring doesn't start on cut line";
				for (int j = 0; j < inner.ring.size(); j++) {
					Coord c = inner.ring.get(j);
					
					if (inner.onDividingLine(c)) {
						if (lastHit != null) {
//							log.error(j - lastHitPos, inner.ring.size() - j, splitParts.size());
							if (j - lastHitPos > 1 && (inner.ring.size() - j > 2 || !splitParts.isEmpty())) {
								List<Coord> ring = new ArrayList<>(inner.ring.subList(lastHitPos, j + 1));
								ring.add(ring.get(0));
								SplitRing sr = new SplitRing(ring, getCutPointHighPrec(), axis);
								splitParts.add(sr);
							}
						}
						lastHit = c;
						lastHitPos = j;
					}
				}
				if (splitParts.size() > 1) {
					drawGpx("mult",  inner.ring);
					Collections.sort(splitParts, new SplitRingComparator());
					for (int j = 0; j < splitParts.size(); j++) {
						SplitRing sp1 = splitParts.get(j);
						if (sp1 == null)
							continue;
						for (int k = j+1; k < splitParts.size(); k++) {
							SplitRing sp2 = splitParts.get(k);
							if (sp2 == null)
								continue;
							if (sp1.min <= sp2.min && sp1.max >= sp2.max) {
								List<Coord> exo = new ArrayList<>(sp2.ring);
								if (sp2.clockwise)
									Collections.reverse(exo);
								drawGpx("exo"+res.size(),  exo);
								res.add(new SplitRing(exo, inner.dividingLine, inner.axis));
								splitParts.set(k, null);
							}
						}
					}
					boolean first = true;
					for (SplitRing sp : splitParts) {
						if (sp == null)
							continue;
						drawGpx("part",  sp.ring);
						if (first) {
							inners.set(i, sp);
							first = false;
						} else 
							newInners.add(sp);
					}
				}
			}
			inners.addAll(newInners);
			return res;
		}

		/**
		 * Connect the inner ways with the outer so that the inner areas are subtracted from the outer areas.
		 * @param outers
		 * @param inners
		 * @param unprocessedInner 
		 * @param isMore
		 * @return true if any outer was changed
		 */
		private boolean subtractInnerFromOuter(List<SplitRing> outers, List<SplitRing> inners, boolean isMore) {
			if (inners.isEmpty())
				return false;
			List<SplitRing> extractedOuter = subtractOuterFromInner(inners, isMore);
			List<SplitRing> sortedInners;
			if (inners.size() > 1) {
				sortedInners = inners.stream().sorted((o1, o2) -> Integer.compare(o1.min, o2.min))
						.collect(Collectors.toList());

				List<SplitRing> overlapped = new ArrayList<>();
				Iterator<SplitRing> iter = sortedInners.iterator();
				SplitRing i1 = iter.next();
				while (iter.hasNext()) {
					SplitRing i2 = iter.next();
					if (i2.max < i1.max) {
						drawGpx("over_i1",  i1.ring);
						drawGpx("over_i2",  i2.ring);
						overlapped.add(i2);
						iter.remove();
					} else 
						i1 = i2;
				}
				if (!overlapped.isEmpty()) {
					boolean modified = false;
					modified |= subtractInnerFromOuter(outers, sortedInners, isMore);
					modified |= subtractInnerFromOuter(extractedOuter, overlapped, isMore);
					if (!modified)
						return false;
					outers.addAll(extractedOuter);
					return true;
				}
				if (axis == CoordinateAxis.LONGITUDE && isMore || axis == CoordinateAxis.LATITUDE && !isMore) {
					Collections.reverse(sortedInners);
				}
			} else {
				sortedInners = new ArrayList<>(inners);
			}
//			for (int i = 0; i < sortedInners.size(); i++) {
//				drawGpx("is"+i,  sortedInners.get(i).ring);
//			}
			
			boolean anyChange = false;

			for (int i = 0; i < outers.size(); i++) {
				SplitRing outer = outers.get(i);
				if (sortedInners.isEmpty())
					break;
				boolean modified = false;
				drawGpx("o"+i,  outer.ring);
				List<Coord> ring = new ArrayList<>();
//				Coord start = outer.ring.get(0);
//				if (outer.onDividingLine(start)) {
//					if (isMore && axis == CoordinateAxis.LONGITUDE && axis.getPosOnAxis(start) != outer.min)
//						lastHitPos = 0;
//					if (isMore && axis == CoordinateAxis.LATITUDE && axis.getPosOnAxis(start) != outer.max)
//						lastHitPos = 0;
//				}
				for(int j = 0; j < outer.ring.size(); j++) {
					Coord c = outer.ring.get(j);
					if (ring.size() > 0 && ring.get(ring.size()-1).highPrecEquals(c))
						continue; // don't add a duplicate
					ring.add(c);
					if (j == 0 || !outer.onDividingLine(c)) {
						continue;
					}
					Coord prevHit = null;
					if (ring.size() > 1) {
						Coord prev = outer.ring.get(j - 1);
						if (!outer.onDividingLine(prev))
							continue;
						prevHit = prev;
					}
					int prevPosOnAxis = axis.getPosOnAxis(prevHit);
					int currPosOnAxis = axis.getPosOnAxis(c);
					
					if (currPosOnAxis == prevPosOnAxis) {
						// not a problem
						// happens when an already merged inner touches the outer at a corner 
					} else {
						Iterator<SplitRing> iter = sortedInners.iterator();
						while (iter.hasNext()) {
							boolean removeSpike = false;
							SplitRing inner = iter.next();
							if (inner.max > outer.max || inner.min < outer.min) {
								// not an inner for this outer
								continue;
							}
							if (currPosOnAxis > prevPosOnAxis) {
								if (inner.min > currPosOnAxis) {
									// all further inner rings have a larger min  
									break;
								}
								if (inner.max < prevPosOnAxis || inner.max > currPosOnAxis) {
									continue;
								}
							} else {
								if (inner.max < currPosOnAxis) {
									continue;
								}
								if (inner.min > prevPosOnAxis || inner.min < currPosOnAxis) {
									continue;
								}
							}
							if (inner.max == currPosOnAxis || inner.max == prevPosOnAxis) {
								// cut might create a spike
								removeSpike = true;
							}
							if (inner.min == currPosOnAxis || inner.min == prevPosOnAxis) {
								// cut might create a spike
								removeSpike = true;
							}
							// found an inner that has touches the cutline between the current points
							drawGpx("i",  inner.ring);
							drawGpx("r1",  ring);
							List<Coord> toAdd;
							if (inner.clockwise != outer.clockwise)
								toAdd = inner.ring;
							else {
								toAdd = new ArrayList<>(inner.ring);
								Collections.reverse(toAdd);
							}
							if (inner.max != inner.min) {
								// remove the line segment on the cutline
								if (inner.ring == toAdd)
									toAdd.remove(toAdd.size()-1);
								else 
									toAdd.remove(0);
							} else {
								// inner touches cutline in a single point, don't remove it
							}
							drawGpx("toAdd",  toAdd);
							// calculate the position where we have to add the points
							boolean addBeforeLast;
							if (currPosOnAxis > prevPosOnAxis) {
								addBeforeLast = (axis == CoordinateAxis.LATITUDE) == isMore;
							} else {
								addBeforeLast = (axis == CoordinateAxis.LATITUDE) != isMore;
							}
							if (addBeforeLast) {
								ring.addAll(ring.size() - 1, toAdd);
							} else {
								ring.addAll(toAdd);
							}
							if (removeSpike) {
								ring = removeObsoletePoints(ring);
							}
							prevPosOnAxis = axis.getPosOnAxis(toAdd.get(toAdd.size()-1));
							modified = true;
							drawGpx("r2",  ring);
							iter.remove();
						}
					}
				}
					
				if (modified) {
					anyChange = true;
					SplitRing sr = new SplitRing(ring, outer.dividingLine, outer.axis);
					drawGpx("mof"+i,  sr.ring);
					outers.set(i, sr);
				}
			}
			
			if (!sortedInners.isEmpty()) {
				for (int i = 0; i < sortedInners.size(); i++) {
					drawGpx("ui"+i,  sortedInners.get(i).ring);
				}
			}
			if (!anyChange)
				return false;
			outers.addAll(extractedOuter);
			return true;
		}

		public List<Coord> getCutLine() {
			List<Coord> cutLine = new ArrayList<>();
			if (axis == CoordinateAxis.LONGITUDE) {
				cutLine.add(
						Coord.makeHighPrecCoord((int) outerBounds.getY() << Coord.DELTA_SHIFT, getCutPointHighPrec()));
				cutLine.add(Coord.makeHighPrecCoord((int) outerBounds.getMaxY() << Coord.DELTA_SHIFT,
						getCutPointHighPrec()));
			} else {
				cutLine.add(
						Coord.makeHighPrecCoord(getCutPointHighPrec(), (int) outerBounds.getX() << Coord.DELTA_SHIFT));
				cutLine.add(Coord.makeHighPrecCoord(getCutPointHighPrec(),
						(int) outerBounds.getMaxX() << Coord.DELTA_SHIFT));
			}
			return cutLine;
		}
				
		public CutPoint duplicate() {
			CutPoint newCutPoint = new CutPoint(this.axis, this.outerBounds);
			newCutPoint.inners.addAll(inners);
			newCutPoint.startPointHp = startPointHp;
			newCutPoint.stopPointHp = stopPointHp;
			return newCutPoint;
		}

		private boolean isGoodCutPoint() {
			// It is better if the cutting line is on a multiple of 2048. 
			// Otherwise MapSource and QLandkarteGT paints gaps between the cuts
			return getCutPointHighPrec() % CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD == 0;
		}
		
		private boolean isBadCutPoint() {
			int d1 = getCutPointHighPrec() - startPointHp;
			int d2 = stopPointHp - getCutPointHighPrec();
			return Math.min(d1, d2) < CUT_POINT_CLASSIFICATION_BAD_THRESHOLD;
		}
		
		private boolean isStartCut() {
			return (startPointHp <= axis.getStartHighPrec(outerBounds));
		}
		
		private boolean isStopCut() {
			return (stopPointHp >= axis.getStopHighPrec(outerBounds));
		}
		
		/**
		 * Calculates the point where the cut should be applied.
		 * @return the point of cut
		 */
		private int getCutPointHighPrec() {
			if (cutPointHp != null) {
				// already calculated => just return it
				return cutPointHp;
			}
			
			if (startPointHp == stopPointHp) {
				// there is no choice => return the one possible point 
				cutPointHp = startPointHp;
				return cutPointHp;
			}
			
			if (isStartCut()) {
				// the polygons can be cut out at the start of the sector and thus adds complexity to the outer polygon
				// without dividing it. That's bad because it makes further splits slower
				cutPointHp = startPointHp;
				return cutPointHp;
			}
			
			if (isStopCut()) {
				// the polygons can be cut out at the start of the sector and thus adds complexity to the outer polygon
				// without dividing it. That's bad because it makes further splits slower
				cutPointHp = stopPointHp;
				return cutPointHp;
			}
			
			// try to cut with a good aspect ratio so try the middle of the polygon to be cut
			int midOuterHp = axis.getStartHighPrec(outerBounds)+(axis.getStopHighPrec(outerBounds) - axis.getStartHighPrec(outerBounds)) / 2;
			cutPointHp = midOuterHp;

			if (midOuterHp < startPointHp) {
				// not possible => the start point is greater than the middle so correct to the startPoint
				cutPointHp = startPointHp;
				
				if (((cutPointHp & ~(CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD-1)) + CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD) <= stopPointHp) {
					cutPointHp = ((cutPointHp & ~(CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD-1)) + CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD);
				}
				
			} else if (midOuterHp > stopPointHp) {
				// not possible => the stop point is smaller than the middle so correct to the stopPoint
				cutPointHp = stopPointHp;

				if ((cutPointHp & ~(CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD-1))  >= startPointHp) {
					cutPointHp = (cutPointHp & ~(CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD-1));
				}
			}
			
			
			// try to find a cut point that is a multiple of 2048 to 
			// avoid that gaps are painted by MapSource and QLandkarteGT
			// between the cutting lines
			int cutMod = cutPointHp % CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD;
			if (cutMod == 0) {
				return cutPointHp;
			}
			
			int cut1 = (cutMod > 0 ? cutPointHp-cutMod : cutPointHp  - CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD- cutMod);
			if (cut1 >= startPointHp && cut1 <= stopPointHp) {
				cutPointHp = cut1;
				return cutPointHp;
			}
			
			int cut2 = (cutMod > 0 ? cutPointHp + CUT_POINT_CLASSIFICATION_GOOD_THRESHOLD -cutMod : cutPointHp - cutMod);
			if (cut2 >= startPointHp && cut2 <= stopPointHp) {
				cutPointHp = cut2;
				return cutPointHp;
			}
			
			return cutPointHp;
		}

		public Rectangle2D getCutRectangleForArea(Rectangle2D areaRect, boolean firstRect) {
			double cp = (double)  getCutPointHighPrec() / (1<<Coord.DELTA_SHIFT);
			if (axis == CoordinateAxis.LONGITUDE) {
				double newWidth = cp-areaRect.getX();
				if (firstRect) {
					return new Rectangle2D.Double(areaRect.getX(), areaRect.getY(), newWidth, areaRect.getHeight()); 
				} else {
					return new Rectangle2D.Double(areaRect.getX()+newWidth, areaRect.getY(), areaRect.getWidth()-newWidth, areaRect.getHeight()); 
				}
			} else {
				double newHeight = cp-areaRect.getY();
				if (firstRect) {
					return new Rectangle2D.Double(areaRect.getX(), areaRect.getY(), areaRect.getWidth(), newHeight); 
				} else {
					return new Rectangle2D.Double(areaRect.getX(), areaRect.getY()+newHeight, areaRect.getWidth(), areaRect.getHeight()-newHeight); 
				}
			}
		}
		
		public List<JoinedWay> getAreas() {
			return inners;
		}

		public void addArea(JoinedWay area) {
			// remove all areas that do not overlap with the new area
			while (!inners.isEmpty() && axis.getStopHighPrec(inners.getFirst()) < axis.getStartHighPrec(area)) {
				// remove the first area
				inners.removeFirst();
			}

			inners.add(area);
			Collections.sort(inners, comparator);
			startPointHp = axis.getStartHighPrec(Collections.max(inners,
				(axis == CoordinateAxis.LONGITUDE ? COMP_LONG_START
						: COMP_LAT_START)));
			stopPointHp = axis.getStopHighPrec(inners.getFirst());
			
			// reset the cached value => need to be recalculated the next time they are needed
			bounds = null;
			cutPointHp = null;
			minAspectRatio = null;
		}

		public int getNumberOfAreas() {
			return this.inners.size();
		}

		/**
		 * Retrieves the minimum aspect ratio of the outer bounds after cutting.
		 * 
		 * @return minimum aspect ratio of outer bound after cutting
		 */
		public double getMinAspectRatio() {
			if (minAspectRatio == null) {
				// first get the left/upper cut
				Rectangle2D r1 = getCutRectangleForArea(outerBounds, true);
				double s1_1 = CoordinateAxis.LATITUDE.getSizeOfSide(r1);
				double s1_2 = CoordinateAxis.LONGITUDE.getSizeOfSide(r1);
				double ar1 = Math.min(s1_1, s1_2) / Math.max(s1_1, s1_2);

				// second get the right/lower cut
				Rectangle2D r2 = getCutRectangleForArea(outerBounds, false);
				double s2_1 = CoordinateAxis.LATITUDE.getSizeOfSide(r2);
				double s2_2 = CoordinateAxis.LONGITUDE.getSizeOfSide(r2);
				double ar2 = Math.min(s2_1, s2_2) / Math.max(s2_1, s2_2);

				// get the minimum
				minAspectRatio = Math.min(ar1, ar2);
			}
			return minAspectRatio;
		}
		
		public int compareTo(CutPoint o) {
			if (this == o) {
				return 0;
			}
			
			// handle the special case that a cut has no area
			if (getNumberOfAreas() == 0) {
				if (o.getNumberOfAreas() == 0) {
					return 0;
				} else {
					return -1;
				}
			} else if (o.getNumberOfAreas() == 0) {
				return 1;
			}
			
			// prefer a cut that is not at the boundaries
			if (isStartCut() && o.isStartCut() == false) {
				return -1;
			} 
			else if (isStartCut() == false && o.isStartCut()) {
				return 1;
			}
			else if (isStopCut() && o.isStopCut() == false) {
				return -1;
			}
			else if (isStopCut() == false && o.isStopCut()) {
				return 1;
			}
			
			if (isBadCutPoint() != o.isBadCutPoint()) {
				if (isBadCutPoint()) {
					return -1;
				} else
					return 1;
			}
			
			double dAR = getMinAspectRatio() - o.getMinAspectRatio();
			if (dAR != 0) {
				return (dAR > 0 ? 1 : -1);
			}
			
			if (isGoodCutPoint() != o.isGoodCutPoint()) {
				if (isGoodCutPoint())
					return 1;
				else
					return -1;
			}
			
			// prefer the larger area that is split
			double ss1 = axis.getSizeOfSide(getBounds2D());
			double ss2 = o.axis.getSizeOfSide(o.getBounds2D());
			if (ss1-ss2 != 0)
				return Double.compare(ss1,ss2); 

			int ndiff = getNumberOfAreas()-o.getNumberOfAreas();
			return ndiff;

		}

		private Rectangle2D getBounds2D() {
			if (bounds == null) {
				// lazy init
				bounds = new Rectangle2D.Double();
				for (JoinedWay a : inners)
					bounds.add(a.getBounds2D());
			}
			return bounds;
		}

		public String toString() {
			return axis +" "+getNumberOfAreas()+" "+startPointHp+" "+stopPointHp+" "+getCutPointHighPrec();
		}
	}

	private static enum CoordinateAxis {
		LATITUDE(false), LONGITUDE(true);

		private CoordinateAxis(boolean useX) {
			this.useX = useX;
		}

		private final boolean useX;

		public int getStartHighPrec(JoinedWay ring) {
			return getStartHighPrec(ring.getBounds2D());
		}

		public int getStartHighPrec(Rectangle2D rect) {
			double val = (useX ? rect.getX() : rect.getY());
			return (int)Math.round(val * (1<<Coord.DELTA_SHIFT));
		}

		public int getStopHighPrec(JoinedWay ring) {
			return getStopHighPrec(ring.getBounds2D());
		}

		public int getStopHighPrec(Rectangle2D rect) {
			double val = (useX ? rect.getMaxX() : rect.getMaxY());
			return (int)Math.round(val * (1<<Coord.DELTA_SHIFT));
		}
		
		public double getSizeOfSide(Rectangle2D rect) {
			if (useX) {
				int latHp = (int)Math.round(rect.getY() * (1<<Coord.DELTA_SHIFT));
				Coord c1 = Coord.makeHighPrecCoord(latHp, getStartHighPrec(rect));
				Coord c2 = Coord.makeHighPrecCoord(latHp, getStopHighPrec(rect));
				return c1.distance(c2);
			} else {
				int lonHp = (int)Math.round(rect.getX() * (1<<Coord.DELTA_SHIFT));
				Coord c1 = Coord.makeHighPrecCoord(getStartHighPrec(rect), lonHp);
				Coord c2 = Coord.makeHighPrecCoord(getStopHighPrec(rect), lonHp);
				return c1.distance(c2);
			}
		}
		
		int getPosOnAxis(Coord c) {
			return useX ? c.getHighPrecLat() : c.getHighPrecLon();
		}
	}
	
	private static final RingComparator COMP_LONG_START = new RingComparator(
			true, CoordinateAxis.LONGITUDE);
	private static final RingComparator COMP_LONG_STOP = new RingComparator(
			false, CoordinateAxis.LONGITUDE);
	private static final RingComparator COMP_LAT_START = new RingComparator(
			true, CoordinateAxis.LATITUDE);
	private static final RingComparator COMP_LAT_STOP = new RingComparator(
			false, CoordinateAxis.LATITUDE);

	private static class RingComparator implements Comparator<JoinedWay> {

		private final CoordinateAxis axis;
		private final boolean startPoint;

		public RingComparator(boolean startPoint, CoordinateAxis axis) {
			this.startPoint = startPoint;
			this.axis = axis;
		}

		public int compare(JoinedWay o1, JoinedWay o2) {
			if (o1 == o2) {
				return 0;
			}

			if (startPoint) {
				int cmp = axis.getStartHighPrec(o1) - axis.getStartHighPrec(o2);
				if (cmp == 0) {
					return axis.getStopHighPrec(o1) - axis.getStopHighPrec(o2);
				} else {
					return cmp;
				}
			} else {
				int cmp = axis.getStopHighPrec(o1) - axis.getStopHighPrec(o2);
				if (cmp == 0) {
					return axis.getStartHighPrec(o1) - axis.getStartHighPrec(o2);
				} else {
					return cmp;
				}
			}
		}

	}
	
	/**
	 * Remove obsolete points on straight lines and spikes
	 * @param points list of coordinates that form a shape
	 * @return possibly reduced list 
	 */
	private static List<Coord> removeObsoletePoints(List<Coord> points) {
		List<Coord> modifiedPoints = new ArrayList<>(points.size());
		
		int n = points.size();
		// scan through the way's points looking for points which are
		// on almost straight line and therefore obsolete
		for (int i = 0; i+1 < points.size(); i++) {
			Coord c1;
			if (modifiedPoints.size() > 0)
				c1 = modifiedPoints.get(modifiedPoints.size()-1);
			else {
				c1 = (i > 0) ? points.get(i-1):points.get(n-2);
			}
			Coord cm = points.get(i);
			if (cm.highPrecEquals(c1)){
				if (modifiedPoints.size() > 1){
					modifiedPoints.remove(modifiedPoints.size()-1);
					c1 = modifiedPoints.get(modifiedPoints.size()-1); // might be part of spike
				} else {
					continue;
				}
			}
			Coord c2 = points.get(i+1);
			int straightTest = Utils.isHighPrecStraight(c1, cm, c2);
			if (straightTest == Utils.STRICTLY_STRAIGHT || straightTest == Utils.STRAIGHT_SPIKE){
				continue;
			}
			modifiedPoints.add(cm);
		}
		if (modifiedPoints.size() > 1 && modifiedPoints.get(0) != modifiedPoints.get(modifiedPoints.size()-1))
			modifiedPoints.add(modifiedPoints.get(0));
		return modifiedPoints;
	}

	private static void drawGpx(String name, List<Coord> points) {
		if (debugGpx) {
			GpxCreator.createGpx("e:/ld/" + name, points);
		}
	}
}
