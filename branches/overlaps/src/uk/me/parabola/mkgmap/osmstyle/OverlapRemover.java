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
package uk.me.parabola.mkgmap.osmstyle;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.net.AccessTagsAndBits;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.LineClipper;
import uk.me.parabola.mkgmap.reader.osm.RestrictionRelation;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.MultiHashMap;
import uk.me.parabola.util.MultiIdentityHashMap;

/**
 * Class to find and report overlapping road segments. Eventually remove the overlap to reduce possible routing problems.
 * @author GerdP
 *
 */
public class OverlapRemover {
	private static final Logger log = Logger.getLogger(OverlapRemover.class);

	private final Area bbox;
	
	private List<ConvertedWay> roads;
	private MultiHashMap<Long, RestrictionRelation> wayRestrictionsMap;
	private int idx;

	private int removedSegments;

	public OverlapRemover(Area bbox) {
		this.bbox = bbox;
	}

	/**
	 * Find and report overlapping road segments. Eventually remove the overlap to reduce possible routing problems. 
	 * @param roads list of roads, elements might be set to null by this method
	 * @param lines list of non-routable ways  
	 * @param modifiedRoads Will be enlarged by all roads modified in this method 
	 * @param restrictions Map with restriction relations 
	 */
	public void processWays(List<ConvertedWay> roads, List<RestrictionRelation> restrictions ) {
		long t1 = System.currentTimeMillis();
		this.roads = roads;
		this.idx = roads.size();
		wayRestrictionsMap = new MultiHashMap<>();
		HashSet<Way> dups = findDups();
		if (!dups.isEmpty()) {
			handleDups(dups, restrictions);
		}
		roads = null;
		wayRestrictionsMap = null;
		long dt = System.currentTimeMillis() - t1;
		log.info("check for overlapping road segments took", dt, "ms");
	}

	/**
	 * Find road segments shared by multiple OSM ways. 
	 * @param roads list of roads
	 * @return list of shared segments
	 */
	private HashSet<Way> findDups() {
		MultiIdentityHashMap<Coord, Segment> node2SegmentMap = new MultiIdentityHashMap<>(); 
		List<Segment> dups = new ArrayList<>();
		Way lastWay = null;
		for (ConvertedWay cw : roads) {
			if (!cw.isValid() || cw.isOverlay()) 
				continue;
			Way way = cw.getWay();
			if (way.equals(lastWay)) 
				continue;
			lastWay = way;
			List<Coord> points = way.getPoints();
			int last = points.size();
			// TODO: add check against bbox to avoid splitting roads outside of it
			for (int j = 0; j + 1 < last; j++) {
				Coord p1 = points.get(j);
				if (p1.getHighwayCount() <= 1) 
					continue;
				Coord p2 = points.get(j + 1);
				if (p2.getHighwayCount() > 1) {
					boolean added = false;
					List<Segment> segments = new ArrayList<>();
					segments.addAll(node2SegmentMap.get(p1));
					segments.addAll(node2SegmentMap.get(p2));
					for (Segment s : segments) {
						if (s.p1 == p1 && s.p2 == p2 || s.p2 == p1 && s.p1 == p2) {
							s.ways.add(way);
							added = true;
							dups.add(s);
						}
					}
					if (!added) {
						Segment s = new Segment(p1, p2);
						s.ways.add(way);
						node2SegmentMap.add(p1, s);
					}
				}
			}
		}
		HashSet<Way> dupWays = new LinkedHashSet<>();
		for (Segment s : dups) {
			// check bbox
			if (!bbox.contains(s.p1) || !bbox.contains(s.p2)) {
				List<Coord> line = new ArrayList<>();
				line.add(s.p1);
				line.add(s.p2);
				List<List<Coord>> lineSegs = LineClipper.clip(bbox, line);
				if (lineSegs.isEmpty()) {
					// overlap is outside of bbox, ignore it
					continue;
				}
				log.info("found overlapping road segments",s.ways.get(0).toBrowseURL(),s.ways.get(1).toBrowseURL());
				
			}
			
			dupWays.addAll(s.ways);
		}
		return dupWays;
	}
	
	/**
	 * Extract the osm ways that have shared segments and split them.
	 * @param dups
	 * @param restrictions 
	 */
	private void handleDups(HashSet<Way> dups, List<RestrictionRelation> restrictions) {
		// get all restriction relations
		// eventually they must be modified if one of its ways is split
		for (RestrictionRelation rrel : restrictions) {
			if (rrel.isValid()) {
				for (Long wayId : rrel.getWayIds()) {
					wayRestrictionsMap.add(wayId, rrel);
				}
			}
		}
		
		splitWaysAtNodes(dups);
	}
	
	/**
	 * Split ways which have shared segments. Some segments may be removed later.
	 * Remaining segments are connected by RoadMerger.
	 * @param splitWays
	 */
	private void splitWaysAtNodes(HashSet<Way> splitWays) {
		Map<Way, List<Way>> modWays = new LinkedHashMap<>();
		for (Way w : splitWays) {
			List<Coord> seq = new ArrayList<>();
			List<Way> parts = new ArrayList<>();
			for (int i = 0; i < w.getPoints().size(); i++) {
				Coord p = w.getPoints().get(i);
				seq.add(p);
				if (p.getHighwayCount() > 1) {
					if (seq.size() > 1) {
						parts.add(createWayPart(w, seq));
					}
					seq = new ArrayList<>();
					seq.add(p);
				}
			}
			if (seq.size() > 1) {
				// we get here when the end of an unclosed way is not connected to other roads
				parts.add(createWayPart(w, seq));
			}
			log.debug("Way", w.getId(), "was split into", parts.size(), "parts");
			modWays.put(w, parts);
		}
		Map<Way, List<ConvertedWay>> modRoads= new LinkedHashMap<>();
		for (ConvertedWay cw : roads) {
			List<Way> parts = modWays.get(cw.getWay());
			if (parts != null) {
				List<ConvertedWay> roadParts = new ArrayList<>();
				for (Way part : parts ) {
					ConvertedWay cwPart = new ConvertedWay(idx++,part,cw.getGType());
					// TODO: overlay handling ?
					roadParts.add(cwPart);
				}
				modRoads.put(cw.getWay(), roadParts);
			}
		}
		int origBad = modRoads.size();
		removeOverlaps(modRoads);
		if (modRoads.isEmpty()) {
			log.warn("could not change any of the",origBad,"road(s) with overlapping segments");
		} else {
			Iterator<ConvertedWay> iter = roads.iterator();
			List<ConvertedWay> roadParts = new ArrayList<>(); 
			while (iter.hasNext()) {
				ConvertedWay origRoad = iter.next();
				for (Entry<Way, List<ConvertedWay>> entry : modRoads.entrySet()) {
					if (entry.getKey() == origRoad.getWay()) {
						if (entry.getValue().size() == 1) {
							List<Coord> oldPoints = origRoad.getWay().getPoints();
							List<Coord> modPoints = entry.getValue().get(0).getWay().getPoints();
							if (oldPoints.equals(modPoints)) {
								// we get here if all points are equal, only other attributes like labels changed 
								continue;
							}
						}
						updateRels(origRoad, entry.getValue());
						iter.remove();
						log.info("removed part(s) of",origRoad);
						roadParts.addAll(entry.getValue());
					}
				}
			}
			roads.addAll(roadParts);
			log.info("changed",modRoads.size(),"of",origBad,"roads with overlapping segments and remvoed",removedSegments,"segments");
		}
		
	}

	/**
	 * Check restriction relations. 
	 * @param origRoad road that is changed or removed
	 * @param parts remaining segments of road
	 */
	private void updateRels(ConvertedWay origRoad, List<ConvertedWay> parts) {
		List<RestrictionRelation> rels = wayRestrictionsMap.get(origRoad.getWay().getId());
		if (rels == null || rels.isEmpty()) 
			return;
		rels = new ArrayList<>(rels);
		
		for (RestrictionRelation rr : rels) {
			if (!rr.isValid())
				continue;
			if (rr.removeWayAndCheck(origRoad.getWay().getId()) == false) {
				if (parts.isEmpty())
					log.warn("ignoring restriction relation",rr,"because way",origRoad.getWay().getId(),"was removed");
				else 
					log.warn("ignoring restriction relation",rr,"because part of way",origRoad.getWay().getId(),"was removed");
			}
			//TODO: 
//			if (parts.isEmpty())
//				if (rr.removeWayAndCheck(origRoad.getWay().getId()) == false) {
//					log.error("ignoring restriction relation",rr,"because way",origRoad.getWay().getId(),"was removed");
//				}
//			else {
//				Way oldWay = origRoad.getWay();
//				Coord p1 = oldWay.getPoints().get(0);
//				Coord p2 = oldWay.getPoints().get(oldWay.getPoints().size()-1);
//				boolean foundReplacement = false;
//				for (ConvertedWay part : parts) {
//					Way newWay = part.getWay();
//					for (int i = 0; i < 2; i++) {
//						// select either first or last point
//						Coord p = newWay.getPoints().get(i == 0? 0: newWay.getPoints().size()-1);
//						if (p.isViaNodeOfRestriction()) {
//							if (p == p1 || p == p2) {
//								List<Coord> viaCoords = rr.getViaCoords();
//								for (Coord via : viaCoords){
//									if (via == p) {
//										if (rr.isToWay(oldWay.getId())) {
//											log.debug("Change to-way",oldWay.getId(),"to",newWay.getId(),"for relation",rr.getId(),"at",p.toOSMURL());
//											rr.replaceWay(oldWay.getId(), newWay.getId());
//											wayRestrictionsMap.removeMapping(oldWay.getId(), rr);
//											wayRestrictionsMap.add(newWay.getId(), rr);
//
//										} else if (rr.isFromWay(oldWay.getId())){
//											log.debug("Change from-way",oldWay.getId(),"to",newWay.getId(),"for relation",rr.getId(),"at",p.toOSMURL());
//											rr.replaceWay(oldWay.getId(), newWay.getId());
//											wayRestrictionsMap.removeMapping(oldWay.getId(), rr);
//											wayRestrictionsMap.add(newWay.getId(), rr);
//										} 
//									}
//								}
//
//							}
//						}
//					}
//				}
//				if (!foundReplacement) {
//					if (rr.removeWayAndCheck(origRoad.getWay().getId()) == false) {
//						log.error("ignoring restriction relation",rr,"because related part of way",origRoad.getWay().getId(),"was removed");
//					}
//				}
//			}
		}
		
	}

	/**
	 * Iterate through all possible combinations of two road segments and try to remove overlaps
	 * @param modRoads
	 */
	private void removeOverlaps(Map<Way, List<ConvertedWay>> modRoads) {
		List<Way> ways = new ArrayList<>(modRoads.keySet());
		for (Way w : ways) {
			log.warn("routable line overlaps other routable line",w.toBrowseURL());
		}
		BitSet mod = new BitSet(ways.size());
		for (int i = 0; i+1 < ways.size(); i++) {
			Way w1 = ways.get(i);
			List<ConvertedWay> parts1 = modRoads.get(w1);
			for (int j = i+1; j < ways.size(); j++) {
				Way w2 = ways.get(j);
				List<ConvertedWay> parts2 = modRoads.get(w2);
				Iterator<ConvertedWay> iter1 = parts1.iterator();
				while (iter1.hasNext()) {
					ConvertedWay r1 = iter1.next();
					if (r1.getWay().getPoints().size() != 2)
						continue;
					Map<String, String> r1Labels = r1.getWay().getTagsWithPrefix("mkgmap:label:", false);
					Iterator<ConvertedWay> iter2 = parts2.iterator();
					while (iter2.hasNext()) {
						ConvertedWay r2 = iter2.next();
						if (r1.getWay().getPoints().size() != 2)
							continue;
						if (isSameSegment(r1,r2) == false)
							continue;
						int res = checkRemove(r1, r2);
						if (res == 0)
							continue;
						// routing attributes are OK, check labels
						Map<String, String> r2Labels = r2.getWay().getTagsWithPrefix("mkgmap:label:", false);
						if (!r1Labels.equals(r2Labels)) {
							Map<String, String> mergedLabels =  new HashMap<>();
							mergedLabels.putAll(r1Labels);
							for (int k = 1; k < 5; k++) {
								String label = r2Labels.get("mkgmap:label:" + k);
								if (label == null)
									break;
								if (r1Labels.containsValue(label) == false) {
									mergedLabels.put("mkgmap:label:" + (k+r1Labels.size()) ,label);
								}
							}
							if (mergedLabels.size() > 4) {
								log.warn("did not remove overlap, too many labels after merging",r1,r2);
								continue;
							}
							// we remove the way segment with the labels, copy them to the other segment
							if (res == 1) {
								if (!r2Labels.equals(mergedLabels)) {
									for (Entry<String, String> entry : mergedLabels.entrySet()) {
										r2.getWay().addTag(entry.getKey(), entry.getValue());
									}
									mod.set(j);
								}
							} else if (res == 2) {
								if (!r1Labels.equals(mergedLabels)) {
									for (Entry<String, String> entry : mergedLabels.entrySet()) {
										r1.getWay().addTag(entry.getKey(), entry.getValue());
									}
									r1Labels = mergedLabels;
									mod.set(i);
								}
							}
						}
						removedSegments++;
						if (res == 1) {
							mod.set(i);
							iter1.remove();
							break;
						} else {
							mod.set(j);
							iter2.remove();
						}
					}
				}
			}
		}
		if (mod.isEmpty()) {
			modRoads.clear();
			return;
		}
		// try to recombine parts
		for (int i = 0; i < ways.size(); i++) {
			Way w = ways.get(i);
			if (!mod.get(i)) {
				modRoads.remove(w);
				continue;
			}

			List<ConvertedWay> parts = modRoads.get(w);
			if (parts.size() <= 1) 
				continue;
			
//			for (int xx = 0; xx < parts.size(); xx++) {
//				GpxCreator.createGpx("e:/ld/part"+w.getId()+"_"+xx, parts.get(xx).getPoints());
//			}
			List<ConvertedWay> combined = new ArrayList<>();
			combined.add(parts.get(0));
			ConvertedWay prev = combined.get(0);
			
			for (int j = 1; j < parts.size(); j++) {
				// loop tries also to combine last with first
				ConvertedWay cw = parts.get(j);
				List<Coord> prevPoints = prev.getPoints();
				Coord p1 = prevPoints.get(prevPoints.size()-1);
				Coord p2 = cw.getPoints().get(0);
				if (p1 == p2 && RoadMerger.isMergeable(prev, cw)) {
					// re-combine
					prevPoints.remove(prevPoints.size()-1);
					prevPoints.addAll(cw.getPoints());
				} else {
					combined.add(cw);
					prev = cw;
				}
			}
			if (combined.size() > 1) {
				ConvertedWay first = combined.get(0);
				ConvertedWay last = combined.get(combined.size() - 1);
				Coord p1 = first.getPoints().get(0);
				Coord p2 = last.getPoints().get(last.getPoints().size() - 1);
				if (p1 == p2 && RoadMerger.isMergeable(first, last)) {
					combined.remove(0);
					first.getPoints().remove(0);
					last.getPoints().addAll(first.getPoints());
				}
			}
			parts.clear();
			parts.addAll(combined);
			
		}
	}

	/**
	 * 
	 * @param road1
	 * @param road2
	 * @return true if the two roads have the same single road segment
	 */
	private boolean isSameSegment(ConvertedWay road1, ConvertedWay road2) {
		if (road1.getPoints().size() != 2 || road2.getPoints().size() != 2)
			return false;
		Coord p10 = road1.getPoints().get(0);
		Coord p11 = road1.getPoints().get(1);
		Coord p20 = road2.getPoints().get(0);
		Coord p21 = road2.getPoints().get(1);
		return p10 == p20 && p11 == p21 || p10 == p21 && p11 == p20; 
	}

	private Way createWayPart(Way w, List<Coord> points) {
		Way part = new Way(w.getId(), points);
		part.setFakeId();
		part.copyTags(w);
		log.debug("splitting part of way",w.getId(),"new id:",part.getId());
		return part;
	}
	
	/**
	 * 
	 * @param road1
	 * @param road2
	 * @return 0 if no way is removable, 1 if 1st way should be removed, 2 if 2nd way should be removed.
	 */
	private int checkRemove(ConvertedWay road1, ConvertedWay road2) {
		boolean isSimilar = isSimilar(road1, road2);
		if (isSimilar) {
			int rc = 0;
			Coord p10 = road1.getPoints().get(0);
			Coord p11 = road1.getPoints().get(1);
			Coord p20 = road2.getPoints().get(0);
			Coord p21 = road2.getPoints().get(1);
			if (p10 == p20 && p11 == p21 || p10 == p21 && p11 == p20) {
				if (road1.isOneway()){
					if (road2.isOneway()) {
						// both are oneways: make sure that the direction matches
						if (p10 == p20 && p11 == p21) {
							rc = 3;
						}
					} else {
						rc = 2; // remove 2nd
					}
				} else if (road2.isOneway()) {
					rc = 1;
				} else {
					rc = 3;   
				}
			}
			if (road1.isRoundabout() != road2.isRoundabout()) {
				// keep the roundabout segment
				if (road1.isRoundabout())
					rc &= ~1; 
				else 
					rc &= ~2;
			}
			if (rc != 3)
				return rc;
			boolean a1 = "yes".equals(road1.getWay().getTag("area"));
			boolean a2 = "yes".equals(road2.getWay().getTag("area"));
			if (a1 != a2) {
				if (a1) 
					return 1; 
				else 
					return 2;
			}
			// if road speed is not equal, remove the segment with the lower value
			int d = Integer.compare(road1.getRoadSpeed(), road2.getRoadSpeed());
			if (d < 0)
				return 1;
			else if (d > 0)
				return 2; 
			boolean rr1 = wayRestrictionsMap.containsKey(road1.getWay().getOriginalId());
			boolean rr2 = wayRestrictionsMap.containsKey(road2.getWay().getOriginalId());
			if (rr1 != rr2) {
				if (rr1)
					return 2; 
				else 
					return 1;
			}
			if (rc == 3) {
				// no difference found, remove the 2nd
				return 2;
			}
			return rc;
		}

		return 0;
	}
	
	/**
	 * Compare selected attributes of roads.
	 * @param road1
	 * @param road2
	 * @return true if roads are similar enough 
	 */
	private static boolean isSimilar(ConvertedWay road1, ConvertedWay road2) {
		// check if basic road attributes match
		if (road1.getRoadClass() != road2.getRoadClass())
			return false;
		Way way1 = road1.getWay();
		Way way2 = road2.getWay();

		if (road1.getAccess() != road2.getAccess()) {
			if (log.isDebugEnabled()) {
				AccessTagsAndBits.reportFirstDifferentTag(log, way1, way2, road1.getAccess(),
						road2.getAccess(), AccessTagsAndBits.ACCESS_TAGS);
			}
			return false;
		}
		byte rf1 = road1.getRouteFlags();
		byte rf2 = road2.getRouteFlags();
		// ignore oneway and roundabout flags here
		rf1 |= AccessTagsAndBits.R_ONEWAY;
		rf2 |= AccessTagsAndBits.R_ONEWAY;
		rf1 |= AccessTagsAndBits.R_ROUNDABOUT;
		rf2 |= AccessTagsAndBits.R_ROUNDABOUT;
		
		if (rf1 != rf2) {
			if (log.isDebugEnabled()) {
				AccessTagsAndBits.reportFirstDifferentTag(log,way1, way2, rf1, rf2, AccessTagsAndBits.ROUTE_TAGS);
			}
			return false;
		}
		return true;
	}
	
	/**
	 * Helper class to group overlapping segments with the ways 
	 * @author Gerd Petermann
	 *
	 */
	private static class Segment {
		final Coord p1,p2;
		final ArrayList<Way> ways = new ArrayList<>();

		public Segment(Coord p1, Coord p2) {
			this.p1 = p1;
			this.p2 = p2;
		}
		
		@Override
		public String toString() {
			return ways.toString() + " " + p1.toDegreeString() + " " + p2.toDegreeString();
		}
	}
}
