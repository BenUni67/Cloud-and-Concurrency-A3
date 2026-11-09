package comp3011;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

// This test was written with assistance of ai, as per the guideline

public class FrameProcessorTest {

    private final InfoVideo videoInfo = new InfoVideo("test", 240, 32, 32, 8, 3, 96, 24.0, 24, 41667, 0);

    // tests that the factory creates new processor objects
    @Test
    void testIndependentProcessors() throws Exception {
        List<FrameProcessor> originals = List.of(new FrameNumberer(), new FrameBlackAndWhiter());
        List<FrameProcessor> copies = FrameProcessorObject.createPipeline(originals, videoInfo);

        assertEquals(originals.size(), copies.size());

        for (int i = 0; i < originals.size(); i++) {
            assertNotSame(originals.get(i), copies.get(i));
        }
    }

    // tests that the factory preserves the order and duplicates of chosen effects
    @Test
    void testProcessorOrder() throws Exception {

        List<FrameProcessor> originals = List.of(new FrameNumberer(), new FrameBlackAndWhiter(),new FrameNumberer());

        List<FrameProcessor> copies = FrameProcessorObject.createPipeline(originals, videoInfo);

        List<String> processorNames = copies.stream()
                .map(p -> p.getClass().getSimpleName())
                .toList();

        assertEquals(List.of(
                "FrameNumberer",
                "FrameBlackAndWhiter",
                "FrameNumberer"
        ), processorNames);
    }
}