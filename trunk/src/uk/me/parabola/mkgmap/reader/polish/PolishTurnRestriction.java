/*
 * Copyright (C) 2010, 2012.
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
package uk.me.parabola.mkgmap.reader.polish;

import java.util.Arrays;
import java.util.Map;

import uk.me.parabola.imgfmt.MapFailedException;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.imgfmt.app.net.GeneralRouteRestriction;

/**
 * Holder for each turn restriction definition.
 * @author Supun Jayathilake
 */
public class PolishTurnRestriction {
	private long[] trafficNodes;
	private long[] trafficRoads;
	private byte exceptMask;

    //  Consider as a valid node upon the instantiation.
    private boolean valid = true;

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }
	public byte getExceptMask() {
        return exceptMask;
    }

    public void setExceptMask(byte exceptMask) {
        this.exceptMask = exceptMask;
    }

    @Override
    public String toString() {
        return "TurnRestriction" + trafficNodes;
    }

	public void setTrafficPoints(String idsList) {
		try {
			trafficNodes = parse(idsList);
			if (trafficNodes.length < 3 || trafficNodes.length > 4) 
				setValid(false);
		} catch (NumberFormatException e) {
			setValid(false);
			throw new MapFailedException("invalid list of nod ids " + idsList);
		}
	}

	public void setTrafficRoads(String idsList) {
		try {
			trafficRoads = parse(idsList);
			if (trafficRoads.length < 2 || trafficRoads.length > 3) 
				setValid(false);
		} catch (NumberFormatException e) {
			setValid(false);
			throw new MapFailedException("invalid list of road ids " + idsList);
		}
	}

	public GeneralRouteRestriction toGeneralRouteRestriction(Map<Long, CoordNode> allNodes) {
		for (Long id : trafficNodes) {
			if (!allNodes.containsKey(id))
				return null;
		}
			 
		if (trafficNodes.length == trafficRoads.length + 1) { 
			
			GeneralRouteRestriction grr = new GeneralRouteRestriction("not", getExceptMask(), "polish");
			grr.setFromNode(allNodes.get(trafficNodes[0]));
			grr.setFromWayId(trafficRoads[0]);
			if (trafficNodes.length == 3) {
				grr.setViaNodes(Arrays.asList(allNodes.get(trafficNodes[1])));
			} else {
				grr.setViaNodes(Arrays.asList(allNodes.get(trafficNodes[1]), allNodes.get(trafficNodes[2])));
				grr.setViaWayIds(Arrays.asList(trafficRoads[1]));
			}
			grr.setToNode(allNodes.get(trafficNodes[trafficNodes.length - 1]));
			grr.setToWayId(trafficRoads[trafficRoads.length - 1]);
			return grr;
		}
		return null;
	}

	private long[] parse(String idsValue) {
		String[] ids = idsValue.split(",");
		long[] longs = new long[ids.length];
		for (int i = 0; i < ids.length; i++) {
			longs[i] = Long.parseLong(ids[i]);
		}
		return longs;
	}

}
