package dev.xr.rayneo.probe;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;

public final class ReadingExitJournalCheck {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("reading-exits").toFile();
        ReadingExitJournal.append(root,"session1","answer1","answer_send_completed",100,
            new JSONObject().put("mode","reading_manual_exit"));
        ReadingExitJournal.append(root,"session1","answer1","device_type8_received",140,null);
        ReadingExitJournal.append(root,"session1","answer2","type7_requested",150,
            new JSONObject().put("reason","round_failed"));
        String[] first = new String(Files.readAllBytes(
            new File(root,"session1--answer1.jsonl").toPath()),StandardCharsets.UTF_8).trim().split("\\n");
        String[] second = new String(Files.readAllBytes(
            new File(root,"session1--answer2.jsonl").toPath()),StandardCharsets.UTF_8).trim().split("\\n");
        check(first.length==2,"answer1 event count");
        check(new JSONObject(first[0]).getString("event").equals("answer_send_completed"),"answer1 order");
        check(new JSONObject(first[1]).getString("event").equals("device_type8_received"),"answer1 exit");
        check(second.length==1,"independent answer identity");
        try {
            ReadingExitJournal.append(root,"../escape","answer3","type7_requested",160,null);
            throw new AssertionError("invalid identity was accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("reading exit journal checks passed");
    }
}
