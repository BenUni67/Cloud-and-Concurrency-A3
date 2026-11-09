package comp3011;

import java.util.ArrayList;
import java.util.List;

public class FrameProcessorObject {
	
	// creates a new independent copy of each effect
	public static List<FrameProcessor> createPipeline(List<FrameProcessor> originals, InfoVideo videoInfo) throws Exception {
		List<FrameProcessor> pipeline = new ArrayList<>();
		
		for (FrameProcessor original : originals) {
			FrameProcessor copy = createProcessor(original);
			
			// keeps the same instance number for consistent effct
			copy.setInstanceIndex(original.getInstanceIndex());
			
			// make the new processor with the video info
			copy.initialise(videoInfo);
			pipeline.add(copy);
		}
		return pipeline;
	}
	
	// creates a new processor of same type
	private static FrameProcessor createProcessor(FrameProcessor processor) {
		
        if (processor instanceof FrameNumberer) 
        	return new FrameNumberer();
        if (processor instanceof FrameScratcher) 
        	return new FrameScratcher();
        if (processor instanceof FrameFlickerer) 
        	return new FrameFlickerer();
        if (processor instanceof FrameBlackAndWhiter) 
        	return new FrameBlackAndWhiter();
        if (processor instanceof FrameYellower) 
        	return new FrameYellower();
        if (processor instanceof FrameVignetter) 
        	return new FrameVignetter();
        if (processor instanceof FrameDuster) 
        	return new FrameDuster();
        if (processor instanceof FrameJitterer) 
        	return new FrameJitterer();
        if (processor instanceof FrameMottler) 
        	return new FrameMottler();
        if (processor instanceof FrameBleeder) 
        	return new FrameBleeder();
        if (processor instanceof FramePepperer) 
        	return new FramePepperer();

        throw new IllegalArgumentException("Unknown frame processor: " + processor.getClass());
	}
}



