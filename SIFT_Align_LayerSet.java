import ini.trakem2.display.*;

import mpi.fruitfly.general.*;
import mpi.fruitfly.math.datastructures.*;

import mpi.fruitfly.registration.FloatArray2DSIFT;
import mpi.fruitfly.registration.Model;
import mpi.fruitfly.registration.TModel2D;
import mpi.fruitfly.registration.TRModel2D;
import mpi.fruitfly.registration.Match;
import mpi.fruitfly.registration.ImageFilter;
import mpi.fruitfly.registration.Tile;
import mpi.fruitfly.registration.SimPoint2DMatch;

import ij.plugin.*;
import ij.gui.*;
import ij.*;
import ij.process.*;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Vector;
import java.awt.geom.AffineTransform;
import java.awt.TextField;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

import java.io.*;


public class SIFT_Align_LayerSet implements PlugIn, KeyListener
{
	private static final String[] dimensions = {
		"translation",
		"translation and rotation" };
	private static int dimension = 1;
	
	// steps
	private static int steps = 3;
	// initial sigma
	private static float initial_sigma = 1.6f;
	// feature descriptor size
	private static int fdsize = 8;
	// feature descriptor orientation bins
	private static int fdbins = 8;
	// size restrictions for scale octaves, use octaves < max_size and > min_size only
	private static int min_size = 64;
	private static int max_size = 512;
	// minimal allowed alignment error in px
	private static float min_epsilon = 1.0f;
	// maximal allowed alignment error in px
	private static float max_epsilon = 10.0f;
	private static float inlier_ratio = 0.05f;
	private static float scale = 1.0f;
	
	/**
	 * downscale a grey scale float image using gaussian blur
	 */
	static ImageProcessor downScale( FloatProcessor ip, float s )
	{
		FloatArray2D g = ImageArrayConverter.ImageToFloatArray2D( ip );

		float sigma = ( float )Math.sqrt( 0.25 / s / s - 0.25 );
		float[] kernel = ImageFilter.createGaussianKernel1D( sigma, true );
		
		long start_time = System.currentTimeMillis();
		System.out.print( "Scaling image by " + s + " => gaussian blur with sigma " + sigma + " ..." );
		g = ImageFilter.convolveSeparable( g, kernel, kernel );
		System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );

		ImageArrayConverter.FloatArrayToFloatProcessor( ip, g );
		//ip.setInterpolate( false );
		return ip.resize( ( int )( s * ip.getWidth() ) );
	}
	
	final private TRModel2D estimateModel( Vector< Match > correspondences )
	{
		TRModel2D model = null;
		float epsilon = 0.0f;
		if ( correspondences.size() > TRModel2D.MIN_SET_SIZE )
		{
			int highest_num_inliers = 0;
			int convergence_count = 0;
			do
			{
				epsilon += min_epsilon;
				// 1000 iterations lead to a probability of < 0.01% that only bad data values were found for inlier_ratio = 0.1
				model = TRModel2D.estimateModel(
						correspondences,			//!< point correspondences
						1000,						//!< iterations
						epsilon * scale,			//!< maximal alignment error for a good point pair when fitting the model
						inlier_ratio );				//!< minimal partition (of 1.0) of inliers
						
				// compare the standard deviation of inliers and matches
				if ( model != null )
				{
					int num_inliers = model.getInliers().size();
					if ( num_inliers <= highest_num_inliers )
					{
						++convergence_count;
					}
					else
					{
						convergence_count = 0;
						highest_num_inliers = num_inliers;
					}
					
				}
			}
			while (
					( model == null || convergence_count < 4 ) &&
					epsilon < max_epsilon );
		}
		return model;
	}

	public void run( String args )
	{
		if ( IJ.versionLessThan( "1.37i" ) ) return;
		
		Display front = Display.getFront();
		if ( front == null )
		{
			System.err.println( "no open displays" );
			return;
		}
		
		LayerSet set = front.getLayer().getParent();
		if ( set == null )
		{
			System.err.println( "no open layer-set" );
			return;
		}
		
		GenericDialog gd = new GenericDialog( "Align stack" );
		gd.addNumericField( "steps_per_scale_octave :", steps, 0 );
		gd.addNumericField( "initial_gaussian_blur :", initial_sigma, 2 );
		gd.addNumericField( "feature_descriptor_size :", fdsize, 0 );
		gd.addNumericField( "feature_descriptor_orientation_bins :", fdbins, 0 );
		gd.addNumericField( "minimum_image_size :", min_size, 0 );
		gd.addNumericField( "maximum_image_size :", max_size, 0 );
		gd.addNumericField( "minimal_alignment_error :", min_epsilon, 2 );
		gd.addNumericField( "maximal_alignment_error :", max_epsilon, 2 );
		gd.addNumericField( "inlier_ratio :", inlier_ratio, 2 );
		gd.addChoice( "transformations_to_be_optimized :", dimensions, dimensions[ dimension ] );
		gd.showDialog();
		if (gd.wasCanceled()) return;
		
		steps = ( int )gd.getNextNumber();
		initial_sigma = ( float )gd.getNextNumber();
		fdsize = ( int )gd.getNextNumber();
		fdbins = ( int )gd.getNextNumber();
		min_size = ( int )gd.getNextNumber();
		max_size = ( int )gd.getNextNumber();
		min_epsilon = ( float )gd.getNextNumber();
		max_epsilon = ( float )gd.getNextNumber();
		inlier_ratio = ( float )gd.getNextNumber();
		String dimension_str = gd.getNextChoice();
		
		ArrayList< Layer > layers = set.getLayers();
		ArrayList< Vector< FloatArray2DSIFT.Feature > > featureSets = new ArrayList< Vector< FloatArray2DSIFT.Feature > >();
		
		FloatArray2DSIFT sift = new FloatArray2DSIFT( fdsize, fdbins );
		
		long start_time;
		
		for ( Layer layer : layers )
		{
			// first, intra-layer alignment
			// TODO involve the correlation techniques
			ArrayList< Patch > patches = layer.getDisplayables( Patch.class );
			ArrayList< Tile > tiles = new ArrayList< Tile >();
			
			ImagePlus imp;
			
			// extract SIFT-features in all patches
			// TODO store the feature sets on disk, each of them might be in the magnitude of 10MB large
			for ( Patch patch : patches )
			{
				imp = patch.getProject().getLoader().fetchImagePlus( patch );
				FloatArray2D fa = ImageArrayConverter.ImageToFloatArray2D( imp.getProcessor().convertToByte( true ) );
				ImageFilter.enhance( fa, 1.0f );
				fa = ImageFilter.computeGaussianFastMirror( fa, ( float )Math.sqrt( initial_sigma * initial_sigma - 0.25 ) );
				
				start_time = System.currentTimeMillis();
				System.out.print( "processing SIFT ..." );
				sift.init( fa, steps, initial_sigma, min_size, max_size );
				Vector< FloatArray2DSIFT.Feature > fs = sift.run( max_size );
				Collections.sort( fs );
				System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
				
				System.out.println( fs.size() + " features identified and processed" );
				
				Model model;
				
				if ( dimension_str == "translation" )
					model = new TModel2D();
				else
					model = new TRModel2D();
				
				model.getAffine().setTransform( patch.getAffineTransform() ); 
				Tile tile = new Tile( ( float )fa.width, ( float )fa.height, model );
				tiles.add( tile );
				featureSets.add( fs );
			}
			
			// identify correspondences
			int num_patches = patches.size();
			for ( int i = 0; i < num_patches; ++i )
			{
				Patch current_patch = patches.get( i );
				Tile current_tile = tiles.get( i );
				for ( int j = i + 1; j < num_patches; ++j )
				{
					Patch other_patch = patches.get( j );
					Tile other_tile = tiles.get( j );
					if ( current_patch.intersects( other_patch ) )
					{
						start_time = System.currentTimeMillis();
						System.out.print( "identifying correspondences using brute force ..." );
						Vector< Match > correspondences = FloatArray2DSIFT.createMatches(
									featureSets.get( i ),
									featureSets.get( j ),
									1.25f,
									null,
									Float.MAX_VALUE );
						System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
						
						IJ.log( "Tiles " + i + " and " + j + " have " + correspondences.size() + " potentially corresponding features." );
						
						TRModel2D model = estimateModel( correspondences );
						
						if ( model != null )
						{
							IJ.log( model.getInliers().size() + " of them are good." );
							ArrayList< SimPoint2DMatch > matches = SimPoint2DMatch.fromMatches( model.getInliers() );
							current_tile.addMatches( matches );
							other_tile.addMatches( SimPoint2DMatch.flip( matches ) );
						}
						else
						{
							IJ.log( "None of them are good." );
						}
					}
				}
			}
			
			
			// apply each tiles transformation to its correspondences
			for ( Tile tile : tiles ) tile.update();
			// again, for error and distance correction
			for ( Tile tile : tiles ) tile.update();
			
			boolean changed = true;
			while ( changed )
			{
				changed = false;
				for ( int i = 1; i < num_patches; ++i )
				{
					Tile tile = tiles.get( i );
					tile.update();
					if ( tile.diceBetterModel( 100000, 1.0f ) )
					{
						patches.get( i ).getAffineTransform().setTransform( tile.getModel().getAffine() );
						
						double od = 0.0;
						for ( Tile t : tiles )
						{
							t.update();
							od += t.getDistance();
						}						
						od /= tiles.size();
						
						IJ.showStatus( "displacement: overall => " + od + ", current => " + tile.getDistance() );
						
						changed = true;
					}
					
					// repaint all Displays showing a Layer of the edited LayerSet
					Display.update( set );					
				}
			}
		}
		// // update selection internals in all open Displays
		Display.updateSelection( front );

		// repaint all Displays showing a Layer of the edited LayerSet
		Display.update( set );
	}

	public void keyPressed(KeyEvent e)
	{
		if (
				( e.getKeyCode() == KeyEvent.VK_F1 ) &&
				( e.getSource() instanceof TextField ) )
		{
		}
		else if ( e.getKeyCode() == KeyEvent.VK_ESCAPE )
		{
			return;
		}
	}

	public void keyReleased(KeyEvent e) { }

	public void keyTyped(KeyEvent e) { }
}
