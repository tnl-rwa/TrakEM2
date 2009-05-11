package ini.trakem2.display;

import ini.trakem2.tree.*;
import ini.trakem2.utils.*;
import ini.trakem2.imaging.PatchStack;
import ini.trakem2.vector.VectorString3D;

import ij.ImageStack;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.process.ByteProcessor;
import ij.gui.ShapeRoi;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.measure.Calibration;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.MenuBar;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.CheckboxMenuItem;
import java.awt.Component;
import java.awt.event.ActionListener;
import java.awt.event.ItemListener;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.util.*;
import java.io.File;
import java.awt.geom.AffineTransform;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyListener;

import javax.vecmath.Point3f;
import javax.vecmath.Color3f;
import javax.vecmath.Vector3f;
import javax.media.j3d.View;
import javax.media.j3d.Transform3D;
import javax.media.j3d.PolygonAttributes;

import ij3d.ImageWindow3D;
import ij3d.Image3DUniverse;
import ij3d.Content;
import ij3d.Image3DMenubar;
import customnode.CustomMeshNode;
import customnode.CustomMesh;
import customnode.CustomTriangleMesh;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Callable;


/** One Display3D instance for each LayerSet (maximum). */
public final class Display3D {

	/** Table of LayerSet and Display3D - since there is a one to one relationship.  */
	static private Hashtable ht_layer_sets = new Hashtable();
	/**Control calls to new Display3D. */
	static private Lock htlock = new Lock();

	/** The sky will fall on your head if you modify any of the objects contained in this table -- which is a copy of the original, but the objects are the originals. */
	static public Hashtable getMasterTable() {
		return (Hashtable)ht_layer_sets.clone();
	}

	/** Table of ProjectThing keys versus meshes, the latter represented by List of triangles in the form of thre econsecutive Point3f in the List.*/
	private Hashtable ht_pt_meshes = new Hashtable();

	private Image3DUniverse universe;

	private Lock u_lock = new Lock();

	private LayerSet layer_set;
	private double width, height;
	private int resample = -1; // unset
	static private final int DEFAULT_RESAMPLE = 4;
	/** If the LayerSet dimensions are too large, then limit to max 2048 for width or height and setup a scale.*/
	private double scale = 1.0;
	static private final int MAX_DIMENSION = 1024; // TODO change to LayerSet virtualization size

	private String selected = null;

	// To fork away from the EventDispatchThread
	static private ExecutorService launchers = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

	// To build meshes
	private ExecutorService executors = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

	/*
	static private KeyAdapter ka = new KeyAdapter() {
		public void keyPressed(KeyEvent ke) {
			// F1 .. F12 keys to set tools
			ProjectToolbar.keyPressed(ke);
		}
	};
	*/

	/** Defaults to parallel projection. */
	private Display3D(final LayerSet ls) {
		this.layer_set = ls;
		this.universe = new Image3DUniverse(512, 512); // size of the initial canvas, not the universe itself
		this.universe.getViewer().getView().setProjectionPolicy(View.PERSPECTIVE_PROJECTION); // (View.PERSPECTIVE_PROJECTION);
		computeScale(ls);
		this.universe.show();
		this.universe.getWindow().addWindowListener(new IW3DListener(this, ls));
		// it ignores the listeners:
		//preaddKeyListener(this.universe.getWindow(), ka);
		//preaddKeyListener(this.universe.getWindow().getCanvas(), ka);

		// register
		Display3D.ht_layer_sets.put(ls, this);
	}

	/*
	private void preaddKeyListener(Component c, KeyListener kl) {
		KeyListener[] all = c.getKeyListeners();
		if (null != all) {
			for (KeyListener k : all) c.removeKeyListener(k);
		}
		c.addKeyListener(kl);
		if (null != all) {
			for (KeyListener k : all) c.addKeyListener(k);
		}
	}
	*/

	public Image3DUniverse getUniverse() {
		return universe;
	}

	/* Take a snapshot know-it-all mode. Each Transform3D given as argument gets assigned to the (nearly) homonimous TransformGroup, which have the following relationships:
	 *
	 *  scaleTG contains rotationsTG
	 *  rotationsTG contains translateTG
	 *  translateTG contains centerTG
	 *  centerTG contains the whole scene, with all meshes, etc.
	 *
	 *  Any null arguments imply the current transform in the open Display3D.
	 *
	 *  By default, a newly created Display3D has the scale and center transforms modified to make the scene fit nicely centered (and a bit scaled down) in the given Display3D window. The translate and rotate transforms are set to identity.
	 *
	 *  The TransformGroup instances may be reached like this:
	 *
	 *  LayerSet layer_set = Display.getFrontLayer().getParent();
	 *  Display3D d3d = Display3D.getDisplay(layer_set);
	 *  TransformGroup scaleTG = d3d.getUniverse().getGlobalScale();
	 *  TransformGroup rotationsTG = d3d.getUniverse().getGlobalRotate();
	 *  TransformGroup translateTG = d3d.getUniverse().getGlobalTranslate();
	 *  TransformGroup centerTG = d3d.getUniverse().getCenterTG();
	 *
	 *  ... and the Transform3D from each may be read out indirectly like this:
	 *
	 *  Transform3D t_scale = new Transform3D();
	 *  scaleTG.getTransform(t_scale);
	 *  ...
	 *
	 * WARNING: if your java3d setup does not support offscreen rendering, the Display3D window will be brought to the front and a screen snapshot cropped to it to perform the snapshot capture. Don't cover the Display3D window with any other windows (not even an screen saver).
	 *
	 */
	/*public ImagePlus makeSnapshot(final Transform3D scale, final Transform3D rotate, final Transform3D translate, final Transform3D center) {
		return universe.makeSnapshot(scale, rotate, translate, center);
	}*/

	/** Uses current scaling, translation and centering transforms! */
	/*public ImagePlus makeSnapshotXY() { // aka posterior
		// default view
		return universe.makeSnapshot(null, new Transform3D(), null, null);
	}*/
	/** Uses current scaling, translation and centering transforms! */
	/*public ImagePlus makeSnapshotXZ() { // aka dorsal
		Transform3D rot1 = new Transform3D();
		rot1.rotZ(-Math.PI/2);
		Transform3D rot2 = new Transform3D();
		rot2.rotX(Math.PI/2);
		rot1.mul(rot2);
		return universe.makeSnapshot(null, rot1, null, null);
	}
	*/
	/** Uses current scaling, translation and centering transforms! */
	/*
	public ImagePlus makeSnapshotYZ() { // aka lateral
		Transform3D rot = new Transform3D();
		rot.rotY(Math.PI/2);
		return universe.makeSnapshot(null, rot, null, null);
	}*/

	/*
	public ImagePlus makeSnapshotZX() { // aka frontal
		Transform3D rot = new Transform3D();
		rot.rotX(-Math.PI/2);
		return universe.makeSnapshot(null, rot, null, null);
	}
	*/

	/** Uses current scaling, translation and centering transforms! Opposite side of XZ. */
	/*
	public ImagePlus makeSnapshotXZOpp() {
		Transform3D rot1 = new Transform3D();
		rot1.rotX(-Math.PI/2); // 90 degrees clockwise
		Transform3D rot2 = new Transform3D();
		rot2.rotY(Math.PI); // 180 degrees around Y, to the other side.
		rot1.mul(rot2);
		return universe.makeSnapshot(null, rot1, null, null);
	}*/

	private class IW3DListener extends WindowAdapter {
		private Display3D d3d;
		private LayerSet ls;
		IW3DListener(Display3D d3d, LayerSet ls) {
			this.d3d = d3d;
			this.ls = ls;
		}
		public void windowClosing(WindowEvent we) {
			//Utils.log2("Display3D.windowClosing");
			d3d.executors.shutdownNow();
			/*Object ob =*/ ht_layer_sets.remove(ls);
			/*if (null != ob) {
				Utils.log2("Removed Display3D from table for LayerSet " + ls);
			}*/
		}
		public void windowClosed(WindowEvent we) {
			//Utils.log2("Display3D.windowClosed");
			ht_layer_sets.remove(ls);
		}
	}

	/** Reads the #ID in the name, which is immutable. */
	private ProjectThing find(String name) {
		long id = Long.parseLong(name.substring(name.lastIndexOf('#')+1));
		for (Iterator it = ht_pt_meshes.keySet().iterator(); it.hasNext(); ) {
			ProjectThing pt = (ProjectThing)it.next();
			Displayable d = (Displayable)pt.getObject();
			if (d.getId() == id) {
				return pt;
			}
		}
		return null;
	}

	/** If the layer set is too large in width and height, then set a scale that makes it maximum MAX_DIMENSION in any of the two dimensions. */
	private void computeScale(LayerSet ls) {
		this.width = ls.getLayerWidth();
		this.height = ls.getLayerHeight();
		if (width > MAX_DIMENSION) {
			scale = MAX_DIMENSION / width;
			height *= scale;
			width = MAX_DIMENSION;
		}
		if (height > MAX_DIMENSION) {
			scale = MAX_DIMENSION / height;
			width *= scale;
			height = MAX_DIMENSION;
		}
		//Utils.log2("scale, width, height: " + scale + ", " + width + ", " + height);
	}

	static private boolean check_j3d = true;
	static private boolean has_j3d_3dviewer = false;

	static private boolean hasLibs() {
		if (check_j3d) {
			check_j3d = false;
			try {
				Class p3f = Class.forName("javax.vecmath.Point3f");
				has_j3d_3dviewer = true;
			} catch (ClassNotFoundException cnfe) {
				Utils.log("Java 3D not installed.");
				has_j3d_3dviewer = false;
				return false;
			}
			try {
				Class ij3d = Class.forName("ij3d.ImageWindow3D");
				has_j3d_3dviewer = true;
			} catch (ClassNotFoundException cnfe) {
				Utils.log("3D Viewer not installed.");
				has_j3d_3dviewer = false;
				return false;
			}
		}
		return has_j3d_3dviewer;
	}

	/** Get an existing Display3D for the given LayerSet, or create a new one for it (and cache it). */
	static private Display3D get(final LayerSet ls) {
		synchronized (htlock) {
			htlock.lock();
			try {
				// test:
				if (!hasLibs()) return null;
				//
				Object ob = ht_layer_sets.get(ls);
				if (null == ob) {
					final boolean[] done = new boolean[]{false};
					javax.swing.SwingUtilities.invokeAndWait(new Runnable() { public void run() {
						Display3D ob = new Display3D(ls);
						ht_layer_sets.put(ls, ob);
						done[0] = true;
					}});
					// wait to avoid crashes in amd64
					// try { Thread.sleep(500); } catch (Exception e) {}
					while (!done[0]) {
						try { Thread.sleep(50); } catch (Exception e) {}
					}
					ob = ht_layer_sets.get(ls);
				}
				return (Display3D)ob;
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				// executed even when returning from within the try-catch block
				htlock.unlock();
			}
		}
		return null;
	}

	/** Get the Display3D instance that exists for the given LayerSet, if any. */
	static public Display3D getDisplay(final LayerSet ls) {
		return (Display3D)ht_layer_sets.get(ls);
	}

	static public void setWaitingCursor() {
		for (Iterator it = ht_layer_sets.values().iterator(); it.hasNext(); ) {
			((Display3D)it.next()).universe.getWindow().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		}
	}

	static public void doneWaiting() {
		for (Iterator it = ht_layer_sets.values().iterator(); it.hasNext(); ) {
			((Display3D)it.next()).universe.getWindow().setCursor(Cursor.getDefaultCursor());
		}
	}

	static public Future<List<Content>> show(ProjectThing pt) {
		return show(pt, false, -1);
	}

	static public void showAndResetView(final ProjectThing pt) {
		new Thread() { public void run() {
			setPriority(Thread.NORM_PRIORITY);
			// wait until done
			Future<List<Content>> fu = show(pt, true, -1);
			try {
				fu.get(); // wait until done
			} catch (Exception e) { IJError.print(e); }
			Display3D d3d = (Display3D) ht_layer_sets.get(pt.getProject().getRootLayerSet()); // TODO should change for nested layer sets
			if (null != d3d) {
				d3d.universe.resetView(); // reset the absolute center
				d3d.universe.adjustView(); // zoom out to bring all elements in universe within view
			}
		}}.start();
	}

	/** Scan the ProjectThing children and assign the renderable ones to an existing Display3D for their LayerSet, or open a new one. If true == wait && -1 != resample, then the method returns only when the mesh/es have been added. */
	static public Future<List<Content>> show(final ProjectThing pt, final boolean wait, final int resample) {
		if (null == pt) return null;
		final Callable<List<Content>> c = new Callable<List<Content>>() {
			public List<Content> call() {
		try {
			// scan the given ProjectThing for 3D-viewable items not present in the ht_meshes
			// So: find arealist, pipe, ball, and profile_list types
			final HashSet hs = pt.findBasicTypeChildren();
			if (null == hs || 0 == hs.size()) {
				Utils.log("Node " + pt + " contains no 3D-displayable children");
				return null;
			}

			final List<Content> list = new ArrayList<Content>();

			for (final Iterator it = hs.iterator(); it.hasNext(); ) {
				// obtain the Displayable object under the node
				final ProjectThing child = (ProjectThing)it.next();
				Object obc = child.getObject();
				Displayable displ = obc.getClass().equals(String.class) ? null : (Displayable)obc;
				if (null != displ) {
					if (displ.getClass().equals(Profile.class)) {
						//Utils.log("Display3D can't handle Bezier profiles at the moment.");
						// handled by profile_list Thing
						continue;
					}
					if (!displ.isVisible()) {
						Utils.log("Skipping non-visible node " + displ);
						continue;
					}
				}
				//StopWatch sw = new StopWatch();
				// obtain the containing LayerSet
				Display3D d3d = null;
				if (null != displ) d3d = Display3D.get(displ.getLayerSet());
				else if (child.getType().equals("profile_list")) {
					ArrayList al_children = child.getChildren();
					if (null == al_children || 0 == al_children.size()) continue;
					// else, get the first Profile and get its LayerSet
					d3d = Display3D.get(((Displayable)((ProjectThing)al_children.get(0)).getObject()).getLayerSet());
				} else {
					Utils.log("Don't know what to do with node " + child);
				}
				if (null == d3d) {
					Utils.log("Could not get a proper 3D display for node " + displ);
					return null; // java3D not installed most likely
				}
				if (d3d.ht_pt_meshes.contains(child)) {
					Utils.log2("Already here: " + child);
					continue; // already here
				}
				setWaitingCursor(); // the above may be creating a display
				//sw.elapsed("after creating and/or retrieving Display3D");
				Future<Content> fu = d3d.addMesh(child, displ, resample);
				if (wait) {
					list.add(fu.get());
				}

				//sw.elapsed("after creating mesh");
			}

			return list;

		} catch (Exception e) {
			IJError.print(e);
			return null;
		} finally {
			doneWaiting();
		}
		}};

		return launchers.submit(c);
	}

	static public void resetView(final LayerSet ls) {
		Display3D d3d = (Display3D) ht_layer_sets.get(ls);
		if (null != d3d) d3d.universe.resetView();
	}

	static public void showOrthoslices(Patch p) {
		Display3D d3d = get(p.getLayerSet());
		d3d.adjustResampling();
		//d3d.universe.resetView();
		String title = makeTitle(p) + " orthoslices";
		// remove if present
		d3d.universe.removeContent(title);
		PatchStack ps = p.makePatchStack();
		ImagePlus imp = get8BitStack(ps);
		d3d.universe.addOrthoslice(imp, null, title, 0, new boolean[]{true, true, true}, d3d.resample);
		Content ct = d3d.universe.getContent(title);
		setTransform(ct, ps.getPatch(0));
		ct.setLocked(true); // locks the added content
	}

	static public void showVolume(Patch p) {
		Display3D d3d = get(p.getLayerSet());
		d3d.adjustResampling();
		//d3d.universe.resetView();
		String title = makeTitle(p) + " volume";
		// remove if present
		d3d.universe.removeContent(title);
		PatchStack ps = p.makePatchStack();
		ImagePlus imp = get8BitStack(ps);
		d3d.universe.addVoltex(imp, null, title, 0, new boolean[]{true, true, true}, d3d.resample);
		Content ct = d3d.universe.getContent(title);
		setTransform(ct, ps.getPatch(0));
		ct.setLocked(true); // locks the added content
	}

	static private void setTransform(Content ct, Patch p) {
		final double[] a = new double[6];
		p.getAffineTransform().getMatrix(a);
		Calibration cal = p.getLayerSet().getCalibration();
		// a is: m00 m10 m01 m11 m02 m12
		// d expects: m01 m02 m03 m04, m11 m12 ...
		ct.applyTransform(new Transform3D(new double[]{a[0], a[2], 0, a[4] * cal.pixelWidth,
			                                       a[1], a[3], 0, a[5] * cal.pixelWidth,
					                          0,    0, 1, p.getLayer().getZ() * cal.pixelWidth,
					                          0,    0, 0, 1}));
	}

	/** Returns a stack suitable for the ImageJ 3D Viewer, either 8-bit gray or 8-bit color.
	 *  If the PatchStach is already of the right type, it is returned,
	 *  otherwise a copy is made in the proper type.
	 */
	static private ImagePlus get8BitStack(final PatchStack ps) {
		switch (ps.getType()) {
			case ImagePlus.COLOR_RGB:
				// convert stack to 8-bit color
				return ps.createColor256Copy();
			case ImagePlus.GRAY16:
			case ImagePlus.GRAY32:
				// convert stack to 8-bit
				return ps.createGray8Copy();
			default:
				return ps;
		}
	}

	/** A Material, but avoiding name colisions. */
	static private int mat_index = 1;
	static private class Mtl {
		float alpha = 1;
		float R = 1;
		float G = 1;
		float B = 1;
		String name;
		Mtl(float alpha, float R, float G, float B) {
			this.alpha = alpha;
			this.R = R;
			this.G = G;
			this.B = B;
			name = "mat_" + mat_index;
			mat_index++;
		}
		public boolean equals(Object ob) {
			if (ob instanceof Display3D.Mtl) {
				Mtl mat = (Mtl)ob;
				if (mat.alpha == alpha
				 && mat.R == R
				 && mat.G == G
				 && mat.B == B) {
					return true;
				 }
			}
			return false;
		}
		void fill(StringBuffer sb) {
			sb.append("\nnewmtl ").append(name).append('\n')
			  .append("Ns 96.078431\n")
			  .append("Ka 0.0 0.0 0.0\n")
			  .append("Kd ").append(R).append(' ').append(G).append(' ').append(B).append('\n') // this is INCORRECT but I'll figure out the conversion later
			  .append("Ks 0.5 0.5 0.5\n")
			  .append("Ni 1.0\n")
			  .append("d ").append(alpha).append('\n')
			  .append("illum 2\n\n");
		}
		int getAsSingle() {
			return (int)((R + G + B) / 3 * 255); // something silly
		}
	}

	/** Generates DXF file from a table of ProjectThing and their associated triangles. */
	private String createDXF(Hashtable ht_content) {
		StringBuffer sb_data = new StringBuffer("0\nSECTION\n2\nENTITIES\n");   //header of file
		for (Iterator it = ht_content.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			ProjectThing pt = (ProjectThing)entry.getKey();
			Displayable displ = (Displayable)pt.getObject();
			List triangles = (List)entry.getValue();
			float[] color = displ.getColor().getColorComponents(null);
			Mtl mtl = new Mtl(displ.getAlpha(), color[0], color[1], color[2]);
			writeTrianglesDXF(sb_data, triangles, mtl.name, Integer.toString(mtl.getAsSingle()));
		}
		sb_data.append("0\nENDSEC\n0\nEOF\n");         //TRAILER of the file
		return sb_data.toString();
	}

	/** @param format works as extension as well. */
	private void export(final ProjectThing pt, final String format) {
		if (0 == ht_pt_meshes.size()) return;
		// select file
		File file = Utils.chooseFile("untitled", format);
		if (null == file) return;
		final String name = file.getName();
		String name2 = name;
		if (!name2.endsWith("." + format)) {
			name2 += "." + format;
		}
		File f2 = new File(file.getParent() + "/" + name2);
		int i = 1;
		while (f2.exists()) {
			name2 = name + "_" + i + "." + format;
			f2 = new File(name2);
		}
		Hashtable ht_content = ht_pt_meshes;
		if (null != pt) {
			ht_content = new Hashtable();
			ht_content.put(pt, ht_pt_meshes.get(pt));
		}
		if (format.equals("obj")) {
			String[] data = createObjAndMtl(name2, ht_content);
			Utils.saveToFile(f2, data[0]);
			Utils.saveToFile(new File(f2.getParent() + "/" + name2 + ".mtl"), data[1]);
		} else if (format.equals("dxf")) {
			Utils.saveToFile(f2, createDXF(ht_content));
		}
	}

	/** Wavefront format. Returns the contents of two files: one for materials, another for meshes*/
	private String[] createObjAndMtl(final String file_name, final Hashtable ht_content) {
		StringBuffer sb_obj = new StringBuffer("# TrakEM2 OBJ File\n");
		sb_obj.append("mtllib ").append(file_name).append(".mtl").append('\n');

		Hashtable ht_mat = new Hashtable();

		int j = 1; // Vert indices in .obj files are global, not reset for every object.
				// starting at '1' because vert indices start at one.

		for (Iterator it = ht_content.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next(); // I hate java's gratuituous verbosity
			ProjectThing pt = (ProjectThing)entry.getKey();
			Displayable displ = (Displayable)pt.getObject();
			List triangles = (List)entry.getValue();
			// make material, and see whether it exists already
			float[] color = displ.getColor().getColorComponents(null);
			Mtl mat = new Mtl(displ.getAlpha(), color[0], color[1], color[2]);
			Object mat2 = ht_mat.get(mat);
			if (null != mat2) mat = (Mtl)mat2; // recycling
			else ht_mat.put(mat, mat); // !@#$% Can't get the object in a HashSet easily
			// make list of vertices
			String title = displ.getProject().getMeaningfulTitle(displ).replaceAll(" ", "_").replaceAll("#", "--");
			Hashtable ht_points = new Hashtable(); // because we like inefficiency
			sb_obj.append("o ").append(title).append('\n');
			final int len = triangles.size();
			int[] index = new int[len];
			int k = 0; // iterate over index array, to make faces later
			// j is tag for each new vert, which start at 1 (for some ridiculous reason)
			for (Iterator tt = triangles.iterator(); tt.hasNext(); ) {
				Point3f p = (Point3f)tt.next();
				//no need if coords are not displaced//p = (Point3f)p.clone();
				// check if point already exists
				Object ob = ht_points.get(p);
				if (null != ob) {
					index[k] = ((Integer)ob).intValue();
				} else {
					// new point
					index[k] = j;
					// record
					ht_points.put(p, new Integer(j));
					// append vertex
					sb_obj.append('v').append(' ').append(p.x)
						      .append(' ').append(p.y)
						      .append(' ').append(p.z).append('\n');
					j++;
				}
				k++;
			}
			sb_obj.append("usemtl ").append(mat.name).append('\n');
			sb_obj.append("s 1\n");
			if (0 != len % 3) Utils.log2("WARNING: list of triangles not multiple of 3");
			// print faces
			int len_p = ht_points.size();
			for (int i=0; i<len; i+=3) {
				sb_obj.append('f').append(' ').append(index[i])
					      .append(' ').append(index[i+1])
					      .append(' ').append(index[i+2]).append('\n');
				//if (index[i] > len_p) Utils.log2("WARNING: face vert index beyond range"); // range is from 1 to len_p inclusive
				//if (index[i+1] > len_p) Utils.log2("WARNING: face vert index beyond range");
				//if (index[i+2] > len_p) Utils.log2("WARNING: face vert index beyond range");
				//Utils.log2("j: " + index[i]);
				// checks passed
			}
			sb_obj.append('\n');
		}
		// make mtl file
		StringBuffer sb_mtl = new StringBuffer("# TrakEM2 MTL File\n");
		for (Iterator it = ht_mat.keySet().iterator(); it.hasNext(); ) {
			Mtl mat = (Mtl)it.next();
			mat.fill(sb_mtl);
		}

		return new String[]{sb_obj.toString(), sb_mtl.toString()};
	}

	/** Considers there is only one Display3D for each LayerSet. */
	static public void remove(ProjectThing pt) {
		if (null == pt) return;
		if (null == pt.getObject()) return;
		Object ob = pt.getObject();
		if (!(ob instanceof Displayable)) return;
		Displayable displ = (Displayable)ob;
		Object d3ob = ht_layer_sets.get(displ.getLayerSet()); // TODO profile_list is going to fail here
		if (null == d3ob) {
			// there is no Display3D showing the pt to remove
			Utils.log2("No Display3D contains ProjectThing: " + pt);
			return;
		}
		Display3D d3d = (Display3D)d3ob;
		Object ob_mesh = d3d.ht_pt_meshes.remove(pt);
		if (null == ob_mesh) {
			Utils.log2("No mesh contained within " + d3d + " for ProjectThing " + pt);
			return; // not contained here
		}
		String title = makeTitle(displ);
		//Utils.log(d3d.universe.contains(title) + ": Universe contains " + displ);
		d3d.universe.removeContent(title); // WARNING if the title changes, problems: will need a table of pt vs title as it was when added to the universe. At the moment titles are not editable for basic types, but this may change in the future. TODO the future is here: titles are editable for basic types.
	}

	static private void writeTrianglesDXF(final StringBuffer sb, final List triangles, final String the_group, final String the_color) {

		final char L = '\n';
		final String s10 = "10\n"; final String s11 = "11\n"; final String s12 = "12\n"; final String s13 = "13\n";
		final String s20 = "20\n"; final String s21 = "21\n"; final String s22 = "22\n"; final String s23 = "23\n";
		final String s30 = "30\n"; final String s31 = "31\n"; final String s32 = "32\n"; final String s33 = "33\n";
		final String triangle_header = "0\n3DFACE\n8\n" + the_group + "\n6\nCONTINUOUS\n62\n" + the_color + L;

		final int len = triangles.size();
		final Point3f[] vert = new Point3f[len];
		triangles.toArray(vert);
		for (int i=0; i<len; i+=3) {

			sb.append(triangle_header)

			.append(s10).append(vert[i].x).append(L)
			.append(s20).append(vert[i].y).append(L)
			.append(s30).append(vert[i].z).append(L)

			.append(s11).append(vert[i+1].x).append(L)
			.append(s21).append(vert[i+1].y).append(L)
			.append(s31).append(vert[i+1].z).append(L)

			.append(s12).append(vert[i+2].x).append(L)
			.append(s22).append(vert[i+2].y).append(L)
			.append(s32).append(vert[i+2].z).append(L)

			.append(s13).append(vert[i+2].x).append(L) // repeated point
			.append(s23).append(vert[i+2].y).append(L)
			.append(s33).append(vert[i+2].z).append(L);
		}
	}

	/** Creates a mesh for the given Displayable in a separate Thread. */
	private Future<Content> addMesh(final ProjectThing pt, final Displayable displ, final int resample) {
		final double scale = this.scale;
		FutureTask<Content> fu = new FutureTask<Content>(new Callable<Content>() {
			public Content call() {
				Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
				try {

		// the list 'triangles' is really a list of Point3f, which define a triangle every 3 consecutive points. (TODO most likely Bene Schmid got it wrong: I don't think there's any need to have the points duplicated if they overlap in space but belong to separate triangles.)
		List triangles = null;
		boolean no_culling = false; // don't show back faces when false
		if (displ instanceof AreaList) {
			int rs = resample;
			if (-1 == resample) rs = Display3D.this.resample = adjustResampling(); // will adjust this.resample, and return it (even if it's a default value)
			else rs = Display3D.this.resample;
			triangles = ((AreaList)displ).generateTriangles(scale, rs);
			//triangles = removeNonManifold(triangles);
		} else if (displ instanceof Ball) {
			double[][][] globe = Ball.generateGlobe(12, 12);
			triangles = ((Ball)displ).generateTriangles(scale, globe);
		} else if (displ instanceof Line3D) {
			// Pipe and Polyline
			// adjustResampling();  // fails horribly, needs first to correct mesh-generation code
			triangles = ((Line3D)displ).generateTriangles(scale, 12, 1 /*Display3D.this.resample*/);
		} else if (null == displ && pt.getType().equals("profile_list")) {
			triangles = Profile.generateTriangles(pt, scale);
			no_culling = true;
		}
		// safety checks
		if (null == triangles) {
			Utils.log("Some error ocurred: can't create triangles for " + displ);
			return null;
		}
		if (0 == triangles.size()) {
			Utils.log2("Skipping empty mesh for " + displ.getTitle());
			return null;
		}
		if (0 != triangles.size() % 3) {
			Utils.log2("Skipping non-multiple-of-3 vertices list generated for " + displ.getTitle());
			return null;
		}
		Color color = null;
		float alpha = 1.0f;
		final String title;
		if (null != displ) {
			color = displ.getColor();
			alpha = displ.getAlpha();
			title = makeTitle(displ);
		} else if (pt.getType().equals("profile_list")) {
			// for profile_list: get from the first (what a kludge; there should be a ZDisplayable ProfileList object)
			Object obp = ((ProjectThing)pt.getChildren().get(0)).getObject();
			if (null == obp) return null;
			Displayable di = (Displayable)obp;
			color = di.getColor();
			alpha = di.getAlpha();
			Object ob = pt.getParent().getTitle();
			if (null == ob || ob.equals(pt.getParent().getType())) title = pt.toString() + " #" + pt.getId(); // Project.getMeaningfulTitle can't handle profile_list properly
			else title = ob.toString() + " /[" + pt.getParent().getType() + "]/[profile_list] #" + pt.getId();
		} else {
			title = pt.toString() + " #" + pt.getId();
		}

		Content ct = null;

		no_culling = true; // for ALL

		// add to 3D view (synchronized)
		synchronized (u_lock) {
			u_lock.lock();
			try {
				// craft a unique title (id is always unique)
				if (ht_pt_meshes.contains(pt) || universe.contains(title)) {
					// remove content from universe
					universe.removeContent(title);
					// no need to remove entry from table, it's overwritten below
				}
				// register mesh
				ht_pt_meshes.put(pt, triangles);
				// ensure proper default transform
				//universe.resetView();

				Color3f c3 = new Color3f(color);

				if (no_culling) {
					// create a mesh with the same color and zero transparency (that is, full opacity)
					CustomTriangleMesh mesh = new CustomTriangleMesh(triangles, c3, 0);
					// Set mesh properties for double-sided triangles
					PolygonAttributes pa = mesh.getAppearance().getPolygonAttributes();
					pa.setCullFace(PolygonAttributes.CULL_NONE);
					pa.setBackFaceNormalFlip(true);
					mesh.setColor(c3);
					// After setting properties, add to the viewer
					ct = universe.addCustomMesh(mesh, title);
				} else {
					ct = universe.addTriangleMesh(triangles, c3, title);
				}

				if (null == ct) return null;

				// Set general content properties
				ct.setTransparency(1f - alpha);
				// Default is unlocked (editable) transformation; set it to locked:
				ct.setLocked(true);

			} catch (Exception e) {
				Utils.logAll("Mesh generation failed for " + title + "\"  from " + pt);
				IJError.print(e);
			}
			u_lock.unlock();
		}

		Utils.log2(pt.toString() + " n points: " + triangles.size());

		return ct;

				} catch (Exception e) {
					IJError.print(e);
					return null;
				}

		}});
		executors.submit(fu);
		return fu;
	}

	/** Creates a mesh from the given VectorString3D, which is unbound to any existing Pipe. */
	static public Future<Content> addMesh(final LayerSet ref_ls, final VectorString3D vs, final String title, final Color color) {
		return addMesh(ref_ls, vs, title, color, null, 1.0f);
	}

	/** Creates a mesh from the given VectorString3D, which is unbound to any existing Pipe. */
	static public Future<Content> addMesh(final LayerSet ref_ls, final VectorString3D vs, final String title, final Color color, final double[] widths, final float alpha) {
		final FutureTask<Content> fu = new FutureTask<Content>(new Callable<Content>() {
			public Content call() {
				Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
				try {
		/////
		final Display3D d3d = Display3D.get(ref_ls);
		final double scale = d3d.scale;
		final double width = d3d.width;
		float transp = 1 - alpha;
		if (transp < 0) transp = 0;
		if (transp > 1) transp = 1;
		if (1 == transp) {
			Utils.log("WARNING: adding a 3D object fully transparent.");
		}

		double[] wi = widths;
		if (null == widths) {
			wi = new double[vs.getPoints(0).length];
			//Utils.log2("len: " + wi.length + vs.getPoints(0).length + vs.getPoints(1).length);
			Arrays.fill(wi, 2.0);
		} else if (widths.length != vs.length()) {
			Utils.log("ERROR: widths.length != VectorString3D.length()");
			return null;
		}

		List triangles = Pipe.generateTriangles(Pipe.makeTube(vs.getPoints(0), vs.getPoints(1), vs.getPoints(2), wi, 1, 12, null), scale);

		Content ct = null;

		// add to 3D view (synchronized)
		synchronized (d3d.u_lock) {
			d3d.u_lock.lock();
			try {
				// ensure proper default transform
				//d3d.universe.resetView();
				//
				//Utils.log2(title + " : vertex count % 3 = " + triangles.size() % 3 + " for " + triangles.size() + " vertices");
				//d3d.universe.ensureScale((float)(width*scale));
				ct = d3d.universe.addMesh(triangles, new Color3f(color), title, /*(float)(width*scale),*/ 1);
				ct.setTransparency(transp);
				ct.setLocked(true);
			} catch (Exception e) {
				IJError.print(e);
			}
			d3d.u_lock.unlock();
		}

		return ct;

		/////
				} catch (Exception e) {
					IJError.print(e);
					return null;
				}

		}});


		launchers.submit(new Runnable() { public void run() {
			final Display3D d3d = Display3D.get(ref_ls);
			d3d.executors.submit(fu);
		}});

		return fu;
	}

	// This method has the exclusivity in adjusting the resampling value.
	synchronized private final int adjustResampling() {
		if (resample > 0) return resample;
		final GenericDialog gd = new GenericDialog("Resample");
		gd.addSlider("Resample: ", 1, 20, -1 != resample ? resample : DEFAULT_RESAMPLE);
		gd.showDialog();
		if (gd.wasCanceled()) {
			resample = -1 != resample ? resample : DEFAULT_RESAMPLE; // current or default value
			return resample;
		}
		resample = ((java.awt.Scrollbar)gd.getSliders().get(0)).getValue();
		return resample;
	}

	/** Checks if there is any Display3D instance currently showing the given Displayable. */
	static public boolean isDisplayed(final Displayable d) {
		if (null == d) return false;
		final String title = makeTitle(d);
		for (Iterator it = Display3D.ht_layer_sets.values().iterator(); it.hasNext(); ) {
			Display3D d3d = (Display3D)it.next();
			if (null != d3d.universe.getContent(title)) return true;
		}
		if (d.getClass() == Profile.class) {
			Content content = getProfileContent(d);
		}
		return false;
	}

	/** Checks if the given Displayable is a Profile, and tries to find a possible Content object in the Image3DUniverse of its LayerSet according to the title as created from its profile_list ProjectThing. */
	static public Content getProfileContent(final Displayable d) {
		if (null == d) return null;
		if (d.getClass() != Profile.class) return null;
		Display3D d3d = get(d.getLayer().getParent());
		if (null == d3d) return null;
		ProjectThing pt = d.getProject().findProjectThing(d);
		if (null == pt) return null;
		pt = (ProjectThing) pt.getParent();
		return d3d.universe.getContent(new StringBuffer(pt.toString()).append(" #").append(pt.getId()).toString());
	}

	static public void setColor(final Displayable d, final Color color) {
		launchers.submit(new Runnable() { public void run() {
			final Display3D d3d = getDisplay(d.getLayer().getParent());
			if (null == d3d) return; // no 3D displays open
			d3d.executors.submit(new Runnable() { public void run() {
			Content content = d3d.universe.getContent(makeTitle(d));
				if (null == content) content = getProfileContent(d);
				if (null != content) content.setColor(new Color3f(color));
			}});
		}});
	}

	static public void setTransparency(final Displayable d, final float alpha) {
		if (null == d) return;
		Layer layer = d.getLayer();
		if (null == layer) return; // some objects have no layer, such as the parent LayerSet.
		Object ob = ht_layer_sets.get(layer.getParent());
		if (null == ob) return;
		Display3D d3d = (Display3D)ob;
		String title = makeTitle(d);
		Content content = d3d.universe.getContent(title);
		if (null == content) content = getProfileContent(d);
		if (null != content) content.setTransparency(1 - alpha);
		else if (null == content && d.getClass().equals(Patch.class)) {
			Patch pa = (Patch)d;
			if (pa.isStack()) {
				title = pa.getProject().getLoader().getFileName(pa);
				for (Iterator it = Display3D.ht_layer_sets.values().iterator(); it.hasNext(); ) {
					d3d = (Display3D)it.next();
					for (Iterator cit = d3d.universe.getContents().iterator(); cit.hasNext(); ) {
						Content c = (Content)cit.next();
						if (c.getName().startsWith(title)) {
							c.setTransparency(1 - alpha);
							// no break, since there could be a volume and an orthoslice
						}
					}
				}
			}
		}
	}

	static public String makeTitle(final Displayable d) {
		return d.getProject().getMeaningfulTitle(d) + " #" + d.getId();
	}
	static public String makeTitle(final Patch p) {
		return new File(p.getProject().getLoader().getAbsolutePath(p)).getName()
		       + " #" + p.getProject().getLoader().getNextId();
	}

	/** Remake the mesh for the Displayable in a separate Thread, if it's included in a Display3D
	 *  (otherwise returns null). */
	static public Future<Content> update(final Displayable d) {
		Layer layer = d.getLayer();
		if (null == layer) return null; // some objects have no layer, such as the parent LayerSet.
		Object ob = ht_layer_sets.get(layer.getParent());
		if (null == ob) return null;
		Display3D d3d = (Display3D)ob;
		return d3d.addMesh(d.getProject().findProjectThing(d), d, d3d.resample);
	}

	/*
	static public final double computeTriangleArea() {
		return 0.5 *  Math.sqrt(Math.pow(xA*yB + xB*yC + xC*yA, 2) +
					Math.pow(yA*zB + yB*zC + yC*zA, 2) +
					Math.pow(zA*xB + zB*xC + zC*xA, 2));
	}
	*/

	static public final boolean contains(final LayerSet ls, final String title) {
		final Display3D d3d = getDisplay(ls);
		if (null == d3d) return false;
		return null != d3d.universe.getContent(title);
	}

	static public void destroy() {
		launchers.shutdownNow();
	}

	static public void init() {
		if (launchers.isShutdown()) {
			launchers = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		}
	}
}
