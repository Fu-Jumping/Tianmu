package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.StopReason;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class FrameComposerTest {
    private String image(int width, int height) throws IOException {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    @Test void createsBoundedTimestampedSheet() throws Exception {
        var sheet = new FrameComposer().compose(List.of(new VideoContent.Frame(100, image(32, 32)), new VideoContent.Frame(500, image(64, 32))), VideoAnalysisFlightsTest.control());
        var decoded = ImageIO.read(new ByteArrayInputStream(sheet));
        assertEquals(1280, decoded.getWidth()); assertEquals(400, decoded.getHeight());
    }
    @Test void compactFourFrameSheetPreservesEveryFrameWithoutShrinkingItsCell() throws Exception {
        var colors=List.of(java.awt.Color.RED,java.awt.Color.GREEN,java.awt.Color.BLUE,java.awt.Color.YELLOW);
        var frames=new ArrayList<VideoContent.Frame>();
        for(int i=0;i<colors.size();i++) {
            var input=new BufferedImage(640,360,BufferedImage.TYPE_INT_RGB);var graphics=input.createGraphics();
            graphics.setColor(colors.get(i));graphics.fillRect(0,0,640,360);graphics.dispose();
            var bytes=new ByteArrayOutputStream();ImageIO.write(input,"png",bytes);
            frames.add(new VideoContent.Frame(i*1000L,Base64.getEncoder().encodeToString(bytes.toByteArray())));
        }
        var sheet=ImageIO.read(new ByteArrayInputStream(new FrameComposer().compose(frames,VideoAnalysisFlightsTest.control())));
        assertEquals(1280,sheet.getWidth());assertEquals(800,sheet.getHeight());
        for(int i=0;i<colors.size();i++) {
            var actual=new java.awt.Color(sheet.getRGB(i%2*640+320,i/2*400+180));var expected=colors.get(i);
            assertTrue(Math.abs(actual.getRed()-expected.getRed())<12);
            assertTrue(Math.abs(actual.getGreen()-expected.getGreen())<12);
            assertTrue(Math.abs(actual.getBlue()-expected.getBlue())<12);
        }
    }
    @Test void rejectsInvalidOrOversizedImageBeforeModelRequest() throws Exception {
        for (String value : List.of("not-base64", Base64.getEncoder().encodeToString(new byte[]{1, 2}), image(1281, 1))) {
            assertEquals("INVALID_VIDEO_ANALYSIS", assertThrows(AgentException.class, () -> new FrameComposer().compose(
                    List.of(new VideoContent.Frame(0, value)), VideoAnalysisFlightsTest.control())).code());
        }
    }
    @Test void cancelledCompositionStopsBeforeImageDecoding() {
        var control = VideoAnalysisFlightsTest.control(); control.cancellation().cancel(StopReason.USER_CANCELLED);
        assertEquals("CANCELLED", assertThrows(AgentException.class, () -> new FrameComposer().compose(List.of(new VideoContent.Frame(0, "invalid")), control)).code());
    }
}
