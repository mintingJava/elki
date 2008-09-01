package de.lmu.ifi.dbs.elki.index.tree.spatial;

import de.lmu.ifi.dbs.elki.logging.AbstractLoggable;
import de.lmu.ifi.dbs.elki.logging.LoggingConfiguration;
import de.lmu.ifi.dbs.elki.math.spacefillingcurves.ZCurve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates the required parameters for a bulk split of a spatial index.
 *
 * @author Elke Achtert 
 */
public class BulkSplit<N extends SpatialObject> extends AbstractLoggable {
  /**
   * Available strategies for bulk loading.
   */
  public enum Strategy {
    ZCURVE,
    MAX_EXTENSION
  }

  public BulkSplit() {
    super(LoggingConfiguration.DEBUG);
  }

  /**
   * Partitions the specified feature vectors according to the chosen strategy.
   *
   * @param spatialObjects the spatial objects to be partioned
   * @param minEntries     the minimum number of entries in a partition
   * @param maxEntries     the maximum number of entries in a partition
   * @param strategy       the bulk load strategy
   * @return the partition of the specified spatial objects according to the chosen strategy
   */
  public List<List<N>> partition(List<N> spatialObjects, int minEntries, int maxEntries,
                                             Strategy strategy) {
    if (strategy == Strategy.MAX_EXTENSION) {
      return maximalExtensionPartition(spatialObjects, minEntries, maxEntries);
    }

    else if (strategy == Strategy.ZCURVE) {
      return zValuePartition(spatialObjects, minEntries, maxEntries);
    }

    else throw new IllegalArgumentException("Unknown bulk load strategy!");
  }

  /**
   * Partitions the specified feature vectors where the split axes are the
   * dimensions with maximum extension
   *
   * @param spatialObjects the spatial objects to be partitioned
   * @param minEntries     the minimum number of entries in a partition
   * @param maxEntries     the maximum number of entries in a partition
   * @return the partition of the specified spatial objects
   */
  private List<List<N>> maximalExtensionPartition(List<N> spatialObjects,
                                                              int minEntries, int maxEntries) {
    List<List<N>> partitions = new ArrayList<List<N>>();
    List<N> objects = new ArrayList<N>(spatialObjects);

    while (objects.size() > 0) {
      StringBuffer msg = new StringBuffer();

      // get the split axis and split point
      int splitAxis = chooseMaximalExtendedSplitAxis(objects);
      int splitPoint = chooseBulkSplitPoint(objects.size(), minEntries, maxEntries);
      if (this.debug) {
        msg.append("\nsplitAxis ").append(splitAxis);
        msg.append("\nsplitPoint ").append(splitPoint);
      }

      // sort in the right dimension
      Collections.sort(objects, new SpatialComparator(splitAxis, SpatialComparator.MIN));

      // insert into partition
      List<N> partition = new ArrayList<N>();
      for (int i = 0; i < splitPoint; i++) {
        N o = objects.remove(0);
        partition.add(o);
      }
      partitions.add(partition);

      // copy array
      if (this.debug) {
        msg.append("\ncurrent partition " + partition);
        msg.append("\nremaining objects # ").append(objects.size());
        debugFine(msg.toString());
      }
    }

    if (this.debug) {
      debugFine("\npartitions " + partitions);
    }
    return partitions;
  }

  /**
   * Partitions the spatial objects according to their z-values.
   *
   * @param spatialObjects the spatial objects to be partitioned
   * @param minEntries     the minimum number of entries in a partition
   * @param maxEntries     the maximum number of entries in a partition
   * @return A partition of the spatial objects according to their z-values
   */
  private List<List<N>> zValuePartition(List<N> spatialObjects, int minEntries, int maxEntries) {
    List<List<N>> partitions = new ArrayList<List<N>>();
    List<N> objects = new ArrayList<N>(spatialObjects);

    // get z-values
    List<double[]> valuesList = new ArrayList<double[]>();
    for (SpatialObject o : spatialObjects) {
      double[] values = new double[o.getDimensionality()];
      for (int d = 0; d < o.getDimensionality(); d++) {
        values[d] = o.getMin(d + 1);
      }
      valuesList.add(values);
    }
    if (debug) {
      debugFine(valuesList.toString());
    }
    List<byte[]> zValuesList = ZCurve.zValues(valuesList);

    // map z-values
    final Map<Integer, byte[]> zValues = new HashMap<Integer, byte[]>();
    for (int i = 0; i < spatialObjects.size(); i++) {
      SpatialObject o = spatialObjects.get(i);
      byte[] zValue = zValuesList.get(i);
      zValues.put(o.getID(), zValue);
    }

    // create a comparator
    Comparator<SpatialObject> comparator = new Comparator<SpatialObject>() {
      public int compare(SpatialObject o1, SpatialObject o2) {
        byte[] z1 = zValues.get(o1.getID());
        byte[] z2 = zValues.get(o1.getID());

        for (int i = 0; i < z1.length; i++) {
          byte z1_i = z1[i];
          byte z2_i = z2[i];
          if (z1_i < z2_i) return -1;
          else if (z1_i > z2_i) return +1;
        }
        return o1.getID() - o2.getID();
      }
    };
    Collections.sort(objects, comparator);

    // insert into partition
    while (objects.size() > 0) {
      StringBuffer msg = new StringBuffer();
      int splitPoint = chooseBulkSplitPoint(objects.size(), minEntries, maxEntries);
      List<N> partition = new ArrayList<N>();
      for (int i = 0; i < splitPoint; i++) {
        N o = objects.remove(0);
        partition.add(o);
      }
      partitions.add(partition);

      // copy array
      if (this.debug) {
        msg.append("\ncurrent partition " + partition);
        msg.append("\nremaining objects # ").append(objects.size());
        debugFine(msg.toString());
      }
    }

    if (this.debug) {
      debugFine("\npartitions " + partitions);
    }
    return partitions;
  }

  /**
   * Computes and returns the best split axis. The best split axis
   * is the split axes with the maximal extension.
   *
   * @param objects the spatial objects to be splitted
   * @return the best split axis
   */
  private int chooseMaximalExtendedSplitAxis(List<N> objects) {
    // maximum and minimum value for the extension
    int dimension = objects.get(0).getDimensionality();
    double[] maxExtension = new double[dimension];
    double[] minExtension = new double[dimension];
    Arrays.fill(minExtension, Double.MAX_VALUE);

    // compute min and max value in each dimension
    for (SpatialObject object : objects) {
      for (int d = 1; d <= dimension; d++) {
        double min, max;
        min = object.getMin(d);
        max = object.getMax(d);

        if (maxExtension[d - 1] < max)
          maxExtension[d - 1] = max;

        if (minExtension[d - 1] > min)
          minExtension[d - 1] = min;
      }
    }

    // set split axis to dim with maximal extension
    int splitAxis = -1;
    double max = 0;
    for (int d = 1; d <= dimension; d++) {
      double currentExtension = maxExtension[d - 1] - minExtension[d - 1];
      if (max < currentExtension) {
        max = currentExtension;
        splitAxis = d;
      }
    }
    return splitAxis;
  }

  /**
   * Computes and returns the best split point.
   *
   * @param numEntries the number of entries to be split
   * @param minEntries the number of minimum entries in the node to be split
   * @param maxEntries number of maximum entries in the node to be split
   * @return the best split point
   */
  private int chooseBulkSplitPoint(int numEntries, int minEntries, int maxEntries) {
    if (numEntries < minEntries) {
      throw new IllegalArgumentException("numEntries < minEntries!");
    }

    if (numEntries <= maxEntries) {
      return numEntries;
    }

    else if (numEntries < maxEntries + minEntries) {
      return (numEntries - minEntries);
    }

    else {
      return maxEntries;
    }
  }
}
