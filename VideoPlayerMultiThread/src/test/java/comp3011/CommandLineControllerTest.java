package comp3011;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CommandLineControllerTest {
	// tests that stacked options have the correct order, and duplicates work too
    @Test
    void testMultipleFrameProcessors() {
        CommandLineController controller = new CommandLineController(new String[]{"-nssnf", "--help"});
        List<String> processors = controller.getFrameProcessors().stream().map(p -> p.getClass().getSimpleName()).toList();

        assertEquals(List.of(
                "FrameNumberer",
                "FrameScratcher",
                "FrameScratcher",
                "FrameNumberer",
                "FrameFlickerer"
        ), processors);
    }
    
    // tests that the user can enter multiple options, both short and long
    @Test
    void testDifferentFrameProcessors() {
        CommandLineController controller = new CommandLineController(new String[]{"--jitter-frames", "-f", "--dust-frames", "-d", "--help"});
        List<String> processors = controller.getFrameProcessors().stream().map(p -> p.getClass().getSimpleName()).toList();

        assertEquals(List.of(
                "FrameJitterer",
                "FrameFlickerer",
                "FrameDuster",
                "FrameDuster"
        ), processors);
    }
    
    // tests that invalid options aren't allowed
    @Test
    void testWrongCommand() {
        CommandLineController controller = new CommandLineController(new String[]{"-z", "--help"});
        assertEquals("Unknown option: -z", controller.getErrorMessage());
    }
}