
package org.ohmage.charts;

import org.achartengine.chart.BarChart;
import org.achartengine.model.TimeSeries;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.ohmage.charts.HistogramBase.HistogramRenderer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Date;
import java.util.List;

/**
 * This is a histogram which can show days on the x-axis and a value on the y-axis.
 * @author cketcham
 *
 */
public class Histogram extends BarChart {

	transient private HistogramBase mBase;

	/**
	 * Construct a new Histogram object with the given values.
	 * 
	 * @param context
	 * @param renderer A renderer can be specified
	 * @param values must be an array and have an entry for each day. The last entry
	 * in the array is the value for 'today'. The second to last entry should be
	 * 'yesterday' etc.
	 */
	public Histogram(Context context, HistogramRenderer renderer,  double[] values) {
		super(buildDataSet(values), (renderer != null ? renderer : new HistogramRenderer(context)), BarChart.Type.DEFAULT);
		mBase = new HistogramBase(this);
		mBase.fitData();
		mBase.setDateFormat("MMM d");
	}

	public Histogram(Context context, double[] values) {
		this(context, null, values);
	}

	public Histogram(Context context, HistogramRenderer renderer, double[] data, int color) {
		this(context, renderer, data);
		getRenderer().getSeriesRendererAt(0).setColor(context.getResources().getColor(color));
	}

	/**
	 * This has the same functionality as the super class, except it calls getDateLabel
	 * instead of just getLabel which will format the label as a date
	 * {@inheritDoc}
	 */
	@Override
	protected void drawXLabels(List<Double> xLabels, Double[] xTextLabelLocations, Canvas canvas,
			Paint paint, int left, int top, int bottom, double xPixelsPerUnit, double minX,double maxX) {
		mBase.drawXLabels(xLabels, xTextLabelLocations, canvas, paint, left, top, bottom, xPixelsPerUnit, minX, maxX);
	}

	/**
	 * Builds a dataset with dates as the x value and the value as the y value.
	 * It expects exactly one number for each day. values[0] will be interpreted
	 * as today. values[N] will be interpreted as N days ago.
	 * 
	 * @param values
	 * @return
	 */
	private static XYMultipleSeriesDataset buildDataSet(double[] values) {
		XYMultipleSeriesDataset dataSet = new XYMultipleSeriesDataset();
		XYSeries series = new XYSeries("");
		for (int i = 0; i < values.length; i++)
			series.add(-i, values[i]);
		dataSet.addSeries(series);
		return dataSet;
	}

	/**
	 * Builds an XY multiple time dataset using the provided values.
	 * 
	 * @param titles the series titles
	 * @param xValues the values for the X axis
	 * @param yValues the values for the Y axis
	 * @return the XY multiple time dataset
	 */
	protected XYMultipleSeriesDataset buildDateDataset(String[] titles, List<Date[]> xValues,
			List<double[]> yValues) {
		XYMultipleSeriesDataset dataset = new XYMultipleSeriesDataset();
		int length = titles.length;
		for (int i = 0; i < length; i++) {
			TimeSeries series = new TimeSeries(titles[i]);
			Date[] xV = xValues.get(i);
			double[] yV = yValues.get(i);
			int seriesLength = xV.length;
			for (int k = 0; k < seriesLength; k++) {
				series.add(xV[k], yV[k]);
			}
			dataset.addSeries(series);
		}
		return dataset;
	}
}