package com.xceptance.neodymium.ai.test;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.enabled;
import static com.codeborne.selenide.Condition.not;
import static com.codeborne.selenide.Condition.tagName;
import static com.codeborne.selenide.Selenide.$;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jspecify.annotations.Nullable;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import com.google.genai.Client;
import com.google.genai.types.ComputerUse;
import com.google.genai.types.Content;
import com.google.genai.types.CountTokensResponse;
import com.google.genai.types.Environment;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.Type;
import com.xceptance.neodymium.ai.data.AITestData;
import com.xceptance.neodymium.ai.util.ScreenshotMarker;
import com.xceptance.neodymium.common.testdata.DataItem;
import com.xceptance.neodymium.util.AllureAddons;
import com.xceptance.neodymium.util.Neodymium;
import com.xceptance.neodymium.util.SelenideAddons;

import io.qameta.allure.Allure;

/**
 * Abstract base class for AI-driven browser automation tests.
 * <p>
 * This class integrates Neodymium/Selenide with Google's GenAI (Gemini) to perform "Computer Use" tasks. It acts as an
 * agent that takes a natural language prompt, views the browser via screenshots/DOM, and executes actions (clicks,
 * typing, navigation) via Function Calling.
 * </p>
 */
// @Retry()
public abstract class AbstractAiTest
{
    private static final String gemini_api_key = "";

    // TODO: we need to get this from Neodymiums browser configuration.
    /** Standard viewport height for AI consistency. */
    public static final int viewPortY = 800;

    /** Standard viewport width for AI consistency. */
    public static final int viewPortX = 1600;

    final int TOKEN_LIMIT = 120_000;

    @DataItem
    AITestData data;

    /**
     * The core loop of the AI test.
     * <ol>
     * <li>Initializes the viewport to the correct size.</li>
     * <li>Sets up the GenAI client and system prompts (persona).</li>
     * <li>Enters a loop where it sends the current state (screenshot/DOM) to the AI.</li>
     * <li>Receives a FunctionCall from the AI.</li>
     * <li>Executes the Selenium action corresponding to that function.</li>
     * <li>Feeds the result (success/failure/screenshot) back to the AI.</li>
     * </ol>
     *
     * @throws Exception
     *             if browser interaction or API calls fail.
     */
    public void runAiTest() throws Exception
    {
        // Ensure consistent resolution for coordinate mapping
        scaleViewPortTo(viewPortX, viewPortY);

        // List of test steps (only for debugging right now)
        List<String> testSteps = new ArrayList<String>();
        List<FunctionDeclaration> functionDeclarations = generateFunctionDeclarations();

        // Define the AI Persona and strict operational rules
        Content systemInstruction = Content.fromParts(Part.fromText(systemPrompt()));

        Assert.assertFalse("Enter gemini API key go to Google AI studio to generate one if needed.", StringUtils.isBlank(gemini_api_key));

        try (Client client = new Client.Builder()
                                                 .apiKey(gemini_api_key)
                                                 .build())
        {
            List<Content> history = new ArrayList<>();
            history.add(Content.fromParts(Part.fromText(data.prompt)));

            GenerateContentResponse response = null;
            int safetyCounter = 0;
            boolean testFinished = false;

            StringBuilder log = new StringBuilder();
            try
            {
                // Main interaction loop
                while (true)
                {
                    String model = "gemini-2.5-computer-use-preview-10-2025";

                    manageHistory(client, history, model, functionDeclarations, systemInstruction);
                    
                    // Call Gemini API
                    response = client.models.generateContent(
                                                             model,
                                                             history,
                                                             GenerateContentConfig.builder()
                                                                                  .systemInstruction(systemInstruction)
                                                                                  .tools(Tool.builder()
                                                                                             .functionDeclarations(functionDeclarations)
                                                                                             .computerUse(ComputerUse.builder()
                                                                                                                     .environment(Environment.Known.ENVIRONMENT_BROWSER)
                                                                                                                     .build())
                                                                                             .build())
                                                                                  .build());

                    @Nullable
                    String responseText = response.text();

                    if (StringUtils.isNoneBlank(responseText))
                    {
                        log
                           .append("Thoughts:\n")
                           .append(responseText)
                           .append("\n");
                    }
                    else
                    {
                        history.add(Content.fromParts(Part.fromText("Remember: Always add yout Thoughts in the reuquired xml like format to your repsonse!")));
                    }

                    System.out.println("Full response:" + responseText);

                    // Handle cases where AI stops outputting function calls (Safety or Confusion)
                    if (response.functionCalls() == null || response.functionCalls().isEmpty())
                    {
                        if (testFinished)
                        {
                            System.out.println("### Test finished, nice! ###");
                            break;
                        }
                        else
                        {
                            System.out.println("### No function call received. SafetyCounter: " + safetyCounter++ + " ###");
                            Assert.assertTrue("AI stopped working before the test was officially finished.", safetyCounter < 15);
                            // Nudge the AI to continue if it falls silent
                            history.add(Content.fromParts(Part.fromText("Continue with your task.")));
                            continue;
                        }
                    }

                    if (!response.candidates().isEmpty())
                    {
                        history.add(response.candidates().get().get(0).content().get());
                    }

                    // Process Function Calls requested by AI
                    for (FunctionCall functionCall : response.functionCalls())
                    {
                        try
                        {
                            Map<String, Object> args = functionCall.args().get();

                            log.append("Called Function:\n").append("\t").append(functionCall.name().get()).append("\n").append(args).append("\n");
                            System.out.println("Tester Action ==> " + functionCall.name().get() + " " + args);

                            Map<String, Object> result = new HashMap<>();
                            if (args.containsKey("safety_decision"))
                            {
                                result.put("safety_acknowledgement", "true");
                            }

                            String stepDescription = (String) args.get("description");
                            if (StringUtils.isBlank(stepDescription))
                            {
                                stepDescription = functionCall.name().get() + " " + args;
                                testSteps.add(stepDescription);
                            }
                            else
                            {
                                testSteps.add(stepDescription + "  (" + functionCall.name().get() + " " + args + ")");
                                history.add(Content.fromParts(Part.fromText("Remember: Allways add a description field to each function call.")));
                                result.put("warning", "mandatory description field missing");
                            }

                            safetyCounter = 0;
                            testFinished = Allure.step(stepDescription, () -> {

                                takeScreenshot(functionCall, "_00_before_function"); // Debug screenshot

                                boolean testFinishedInsideStep = false;

                                // --- FUNCTION EXECUTION SWITCH ---
                                switch (functionCall.name().get())
                                {
                                    case "open_web_browser":
                                        result.put("status", "success");
                                        break;
                                    case "navigate":
                                    case "navigate_to_url":
                                        Selenide.open((String) args.get("url"));
                                        result.put("status", "success");
                                        break;
                                    case "go_back":
                                        Selenide.back();
                                        result.put("status", "success");
                                        break;
                                    case "java_method":
                                        runMethodWithReflection((String) args.get("name"), (String) args.get("parameter"));
                                        result.put("status", "success");
                                        break;
                                    case "finish_test":
                                        testFinishedInsideStep = true;
                                        testSteps.add("Summary: " + (String) args.get("summary"));
                                        break;
                                    case "click_element":
                                        try
                                        {
                                            $((String) args.get("selector"))
                                                                            .highlight()
                                                                            .click();
                                            result.put("status", "success");
                                        }
                                        catch (Exception e)
                                        {
                                            result.put("status", "error");
                                            result.put("message", e.getMessage());
                                        }
                                        break;
                                    case "click_at":
                                        int xClick = ((Number) args.get("x")).intValue();
                                        int yClick = ((Number) args.get("y")).intValue();
                                        clickScaledCoords(xClick, yClick);
                                        result.put("status", "success");
                                        break;
                                    case "type_text":
                                        String text = (String) args.get("text");
                                        String selectorForTyping = (String) args.get("selector");
                                        if (selectorForTyping != null)
                                        {
                                            $(selectorForTyping).highlight().type(text);
                                        }
                                        else
                                        {
                                            sendText(text); // Global typing (no specific element)
                                        }
                                        result.put("status", "success");
                                        break;
                                    case "type_text_at":
                                        boolean clearBeforeTyping = (boolean) ((args.get("clear_before_typing") == null) ? false
                                                                                                                         : args.get("clear_before_typing"));
                                        String text_to_type = (String) args.get("text");
                                        boolean press_enter = (Boolean) args.getOrDefault("press_enter", false);
                                        int xType = ((Number) args.get("x")).intValue();
                                        int yType = ((Number) args.get("y")).intValue();

                                        if (clearBeforeTyping == true)
                                        {
                                            clearInputScaledCoords(xType, yType);
                                        }
                                        clickScaledCoords(xType, yType);
                                        sendText(text_to_type);
                                        if (press_enter)
                                        {
                                            Actions actions = new Actions(WebDriverRunner.getWebDriver());
                                            actions.sendKeys(Keys.ENTER).perform();
                                        }
                                        result.put("status", "success");
                                        break;
                                    case "scroll_document":
                                        String direction = (String) args.get("direction");
                                        int magnitude = 800;
                                        if (args.containsKey("magnitude"))
                                        {
                                            magnitude = ((Number) args.get("magnitude")).intValue();
                                        }
                                        if ("down".equals(direction))
                                        {
                                            scroll(0, magnitude);
                                        }
                                        else if ("up".equals(direction))
                                        {
                                            scroll(0, -magnitude);
                                        }
                                        result.put("status", "success");
                                        break;
                                    case "take_screenshot":
                                        byte[] screenshotBytes = new ByteArrayInputStream(((TakesScreenshot) WebDriverRunner.getWebDriver()).getScreenshotAs(OutputType.BYTES)).readAllBytes();
                                        result.put("screenshot-image-bytes", screenshotBytes);
                                        result.put("status", "success");
                                        break;
                                    case "hover_at":
                                        int x = ((Number) args.get("x")).intValue();
                                        int y = ((Number) args.get("y")).intValue();
                                        hoverScaledCoords(x, y);
                                        result.put("status", "success");
                                        break;
                                    case "wait_5_seconds":
                                        Selenide.sleep(5000);
                                        result.put("status", "success");
                                        break;
                                    case "report_issue":
                                        // Stops the test and fails immediately via JUnit assertion
                                        SelenideAddons.wrapAssertionError(() -> Assertions.assertEquals((String) args.get("expectedValue"),
                                                                                                        (String) args.get("actualValue"),
                                                                                                        (String) args.get("errorMessage")));
                                        break;
                                    case "get_page_content":
                                        String cleanedDom = getCleanedDom();
                                        AllureAddons.addAttachmentToStep("DOM Content", "text/html", ".html",
                                                                         new ByteArrayInputStream(cleanedDom.getBytes(StandardCharsets.UTF_8)));

                                        result.put("content", Part.fromText(cleanedDom));
                                        break;
                                    default:
                                        result.put("error", "unsupported function");
                                }

                                if (functionCall.args().get().containsKey("x") && functionCall.args().get().containsKey("y"))
                                {
                                    int x = ((Number) functionCall.args().get().get("x")).intValue();
                                    int y = ((Number) functionCall.args().get().get("y")).intValue();

                                    history.add(Content.fromParts(Part.fromText("Check on the next image if the coordinates you tried to use (" + x + "," + y
                                                                                + ") are where you intendet to act. They are marked with a pink 5x5 square.")));
                                }

                                result.put("url", Neodymium.getDriver().getCurrentUrl());

                                log.append("\t").append("Result:\n").append(result).append("\n").append("\n");
                                return testFinishedInsideStep;
                            });

                            Selenide.sleep(1000);
                            // Capture browser state (as screenshot) to send back to AI
                            byte[] screenshot = takeScreenshot(functionCall, "_10_after_function");// new
                            // ByteArrayInputStream(((TakesScreenshot)
                            // WebDriverRunner.getWebDriver()).getScreenshotAs(OutputType.BYTES)).readAllBytes();

                            // Add result and new screenshot to history
                            history.add(Content.fromParts(
                                                          Part.fromFunctionResponse(functionCall.name().get(), result),
                                                          Part.fromBytes(screenshot, "image/png"),
                                                          Part.fromText("Now let's check if that worked and do the next step.")));

                        }
                        catch (IllegalArgumentException e)
                        {
                            e.printStackTrace();
                            // Fallback for illegal responses/hallucinations from API
                        }

                    }
                }
            }
            finally
            {
                Allure.addAttachment("AI Log", log.toString());
            }
        }
    }

    /***
     * TODO get this from configuration files
     * 
     * @return the system prompt for the client/session.
     */
    private String systemPrompt()
    {
        return """
            **CORE REQUIREMENT: CHAIN OF THOUGHT**
            Before calling ANY function, you MUST output a thought block explaining your reasoning.
            Analyze the current screenshot, check if the previous step succeeded, and plan the exact next step.
            Format your thought like this:
            <thought>
            <analysis>[What do I see? Is the previous step finished?]</analysis>
            <plan>[What is the exact next action?]</plan>
            </thought>

            ALWAYS do one step at a time. Be extremely strict about the defined steps.

            You get a screenshot for each step. If you try to use any coordinates those will be marked with a pink square on the screenshot.
            If something is not working as expected, search for the pink square and adjust your coordinates accordingly.

            GENERAL RULES:
            1. You are automating, stick to the prompt, don't try around
            2. Don't be clever: if something is not working, don't try to find workarounds, but report it
            3. If something does not work as expected use the 'report_issue' tool, to stop the test
            4. NEVER skip a step if the result is not exactly correct
            5. If you want to click based on coordinates always use the center of the element you try to click
            6. If a precondition for a step is not fulfilled ALWAYS use 'report_issue' tool, to stop the test
            7. NEVER output a tool call without a preceding `

            RULES FOR FUNCTIONS:
            1. For EVERY function call add a "description" field containing information what you are doing in this function.
            2. Once the prompt is done use the 'finish_test'
            3. ONLY use the 'finish_test' function if everything from the prompt is done
            4. In the 'finish_test' function give a summary of ALL steps you have done during this test run
            5. If you are asked to use CSS or locator don't use the screenshot but work on the DOM with get_page_content and/or click_element
            6. If something is not working via screenshots get the DOM  via get_page_content function and then use click_element function
            7. If asked to call a java method, use the java_method function, using exactly the name given in the prompt.

            RULES FOR VALIDATION:
            1. Whenever the user asks you to "check", "verify", "assert", or "validate" a value, you MUST NOT reply with text.
            2. Instead, you MUST use the 'report_validation' tool immediately.
            3. Extract the 'actualValue' from the page and compare it to the 'expectedValue' from the prompt.
            4. Set 'isPassing' to true only if they match exactly.


            """;
    }

    /**
     * Generates the list of available tools (functions) the AI can call. This defines the JSON Schema passed to the
     * GenAI model.
     *
     * @return List of FunctionDeclaration objects.
     */
    private List<FunctionDeclaration> generateFunctionDeclarations()
    {
        FunctionDeclaration finishTool = FunctionDeclaration.builder()
                                                            .name("finish_test")
                                                            .description("Terminates the test session. Call this ONLY when you have completed the objective and validated the result.")
                                                            .parameters(
                                                                        Schema.builder()
                                                                              .type(Type.Known.OBJECT)
                                                                              .properties(Map.of(
                                                                                                 "status",
                                                                                                 Schema.builder().type(Type.Known.STRING)
                                                                                                       .enum_(List.of("PASS", "FAIL"))
                                                                                                       .description("The final result of the test").build(),
                                                                                                 "description",
                                                                                                 Schema.builder().type(Type.Known.STRING)
                                                                                                       .description("A very short description of the goal of this function call.")
                                                                                                       .build(),
                                                                                                 "summary",
                                                                                                 Schema.builder().type(Type.Known.STRING)
                                                                                                       .description("A short summary of what happened")
                                                                                                       .build()))
                                                                              .required(List.of("status", "summary", "description"))
                                                                              .build())
                                                            .build();
        FunctionDeclaration getContent = FunctionDeclaration.builder()
                                                            .name("get_page_content")
                                                            .description("Retrieves the DOM or page content from the browser.")
                                                            .parameters(
                                                                        Schema.builder()
                                                                              .type(Type.Known.OBJECT)
                                                                              .properties(Map.of(
                                                                                                 "description",
                                                                                                 Schema.builder().type(Type.Known.STRING)
                                                                                                       .description("A very short description of the goal of this function call.")
                                                                                                       .build()))
                                                                              .required(List.of("description"))
                                                                              .build())
                                                            .build();

        FunctionDeclaration javaMethod = FunctionDeclaration.builder()
                                                            .name("java_method")
                                                            .description("Calls a java method if the prompt demands it.")
                                                            .parameters(
                                                                        Schema.builder()
                                                                              .type(Type.Known.OBJECT)
                                                                              .properties(Map.of(
                                                                                                 "name",
                                                                                                 Schema.builder().type(Type.Known.STRING)
                                                                                                       .description("The name of the mehtod").build(),
                                                                                                 "description",
                                                                                                 Schema.builder().type(Type.Known.STRING)
                                                                                                       .description("A very short description of the goal of this function call.")
                                                                                                       .build(),
                                                                                                 "parameter",
                                                                                                 Schema.builder().type(Type.Known.STRING)
                                                                                                       .description("The parameter needed").build()))
                                                                              .required(List.of("name", "parameter", "description"))
                                                                              .build())
                                                            .build();

        FunctionDeclaration validationTool = FunctionDeclaration.builder()
                                                                .name("report_issue")
                                                                .description("Call this function ONLY when you have extracted the data and are ready to validate the test result.")
                                                                .parameters(
                                                                            Schema.builder()
                                                                                  .type(Type.Known.OBJECT)
                                                                                  .properties(Map.of(
                                                                                                     "actualValue",
                                                                                                     Schema.builder().type(Type.Known.STRING)
                                                                                                           .description("The extracted value").build(),
                                                                                                     "expectedValue",
                                                                                                     Schema.builder().type(Type.Known.STRING)
                                                                                                           .description("The expected value").build(),
                                                                                                     "description",
                                                                                                     Schema.builder().type(Type.Known.STRING)
                                                                                                           .description("A very short description of the goal of this function call.")
                                                                                                           .build(),
                                                                                                     "errorMessage",
                                                                                                     Schema.builder().type(Type.Known.STRING)
                                                                                                           .description("Error details if failing")
                                                                                                           .build()))
                                                                                  .required(List.of("errorMessage", "actualValue", "expectedValue",
                                                                                                    "description"))
                                                                                  .build())
                                                                .build();

        FunctionDeclaration reviewTool = FunctionDeclaration.builder()
                                                            .name("review_step_result")
                                                            .description("Call this function to either approve or reject a step.")
                                                            .parameters(
                                                                        Schema.builder()
                                                                              .type(Type.Known.OBJECT)
                                                                              .properties(Map.of(
                                                                                                 "result",
                                                                                                 Schema.builder().type(Type.Known.BOOLEAN)
                                                                                                       .description("The extracted value").build(),
                                                                                                 "description",
                                                                                                 Schema.builder().type(Type.Known.STRING)
                                                                                                       .description("A very short description of the goal of this function call.")
                                                                                                       .build(),
                                                                                                 "reason",
                                                                                                 Schema.builder().type(Type.Known.STRING)
                                                                                                       .description("The reason why the last step got the according result. Focus on the last step.")
                                                                                                       .build()))
                                                                              .required(List.of("result", "reason", "description"))
                                                                              .build())
                                                            .build();

        FunctionDeclaration clickElement = FunctionDeclaration.builder()
                                                              .name("click_element")
                                                              .description("Extract the matching selector from the DOM and click it.")
                                                              .parameters(
                                                                          Schema.builder()
                                                                                .type(Type.Known.OBJECT)
                                                                                .properties(Map.of(
                                                                                                   "description",
                                                                                                   Schema.builder().type(Type.Known.STRING)
                                                                                                         .description("A very short description of the goal of this function call.")
                                                                                                         .build(),
                                                                                                   "selector",
                                                                                                   Schema.builder().type(Type.Known.STRING)
                                                                                                         .description("The css locator for the desired element")
                                                                                                         .build()))
                                                                                .required(List.of("selector", "description"))
                                                                                .build())
                                                              .build();

        List<FunctionDeclaration> functionDeclarations = List.of(validationTool, finishTool,
                                                                 javaMethod, reviewTool, clickElement, getContent);
        return functionDeclarations;
    }

    /**
     * Extracts the DOM, cleans it using Jsoup, and truncates it if necessary. This reduces token usage for the AI and
     * removes non-interactive elements (script, style, svg).
     *
     * @return A string containing the cleaned HTML DOM.
     */
    public String getCleanedDom()
    {
        String rawHtml = Selenide.executeJavaScript("return document.documentElement.outerHTML;");
        Document doc = Jsoup.parse(rawHtml);

        // Remove heavy elements that don't aid selector logic
        doc.select("script, style, svg, noscript, meta").remove();

        // Optional: remove hidden elements if you only want visible interaction
        doc.select("[style*='display: none']").remove();

        String pageContent = doc.html();

        // // Limit size to avoid context window overflow
        // if (pageContent.length() > 20000)
        // {
        // pageContent = pageContent.substring(0, 20000);
        // }

        String domContent = "Current Page DOM:\n" + pageContent;
        return domContent;
    }

    /**
     * Create a debugging screenshot with a marker at the clicked location. NOTE: These are NOT sent to the AI, but
     * saved locally for human debugging.
     * 
     * @param functionCall
     *            The function call containing coordinates (if any).
     * @return
     */
    private byte[] takeScreenshot(FunctionCall functionCall)
    {
        return takeScreenshot(functionCall, "");
    }

    /**
     * Create a debugging screenshot with a marker.
     * 
     * @param functionCall
     *            The function call containing coordinates.
     * @param additionalMessage
     *            Suffix for the filename.
     * @return
     */
    private byte[] takeScreenshot(FunctionCall functionCall, String additionalMessage)
    {
        if (functionCall.args().get().containsKey("x") && functionCall.args().get().containsKey("y"))
        {
            int x = ((Number) functionCall.args().get().get("x")).intValue();
            int y = ((Number) functionCall.args().get().get("y")).intValue();

            // Map AI coordinates (usually 1000x1000 relative) to screen coordinates
            var scaled = new ScaledCoord(x, y).scaleTo(1000, 1000);

            return ScreenshotMarker.takeScreenshotWithMarker(scaled.x, scaled.y, functionCall.name().get() + additionalMessage);
        }

        return ScreenshotMarker.takeScreenshotWithMarker(-1, -1, functionCall.name().get() + additionalMessage);
    }

    /**
     * Clears an input field at the given coordinates, with safety checks. Only clears if the element is a valid text
     * input (not a checkbox, button, readonly, etc.).
     *
     * @param x
     *            Screen X coordinate
     * @param y
     *            Screen Y coordinate
     */
    private void clearInputScaledCoords(int x, int y)
    {
        SelenideElement element = getElementAt(x, y);
        boolean isTextEntry = element.is(tagName("input")) || element.is(tagName("textarea"));

        // 2. If it is an input, ensure it is not a weird type (like checkbox or color)
        String type = element.getAttribute("type");
        boolean isInvalidType = "checkbox".equals(type) || "radio".equals(type)
                                || "button".equals(type) || "submit".equals(type)
                                || "file".equals(type) || "hidden".equals(type);

        // 3. Check readability/disabled state
        if (isTextEntry && !isInvalidType && element.is(enabled) && element.has(not(attribute("readonly", "true"))))
        {
            element.clear();
        }
        else
        {
            System.out.println("Skipped clearing element: " + element.getTagName());
        }
    }

    /**
     * Forces the browser window to have a specific *inner* content size. This accounts for OS borders, title bars, and
     * toolbars to ensure the coordinate mapping matches exactly what the AI expects.
     *
     * @param targetW
     *            Target viewport width.
     * @param targetH
     *            Target viewport height.
     */
    private void scaleViewPortTo(int targetW, int targetH)
    {
        JavascriptExecutor js = (JavascriptExecutor) Neodymium.getDriver();
        WebDriver.Window window = Neodymium.getDriver().manage().window();

        // Try up to 3 times to get it right (browser resize can be asynchronous/flaky)
        for (int i = 0; i < 3; i++)
        {
            long innerWidth = (Long) js.executeScript("return document.documentElement.clientWidth;");
            long innerHeight = (Long) js.executeScript("return document.documentElement.clientHeight;");

            // If we are already at the correct size, break
            if (innerWidth == targetW && innerHeight == targetH)
            {
                break;
            }

            // Calculate the difference needed
            int widthDiff = targetW - (int) innerWidth;
            int heightDiff = targetH - (int) innerHeight;

            // Get current outer size
            Dimension currentSize = window.getSize();

            // Apply the difference to the outer size
            int newW = currentSize.getWidth() + widthDiff;
            int newH = currentSize.getHeight() + heightDiff;

            window.setSize(new Dimension(newW, newH));
        }
    }

    private void scroll(int x, int y)
    {
        Selenide.executeJavaScript("window.scrollBy(arguments[0], arguments[1]);", x, y);
    }

    private void sendText(String text)
    {
        Actions actions = new Actions(WebDriverRunner.getWebDriver());
        actions.sendKeys(text).perform();
    }

    private void hoverScaledCoords(int x, int y)
    {
        SelenideElement elem = getElementAt(x, y);
        elem.hover();
    }

    private SelenideElement clickScaledCoords(int x, int y)
    {
        SelenideElement elem = getElementAt(x, y);
        elem.click();
        return elem;
    }

    /**
     * Locates the DOM element at the specified coordinates. Scales the generic AI coordinates to the actual viewport
     * before query.
     */
    private SelenideElement getElementAt(int x, int y)
    {
        var scaledCoordForClicking = new ScaledCoord(x, y).scaleTo(1000, 1000);
        WebElement domElement = Selenide.executeJavaScript("var x = arguments[0], y = arguments[1];" +
                                                           "return document.elementFromPoint(x, y);", scaledCoordForClicking.x, scaledCoordForClicking.y);

        SelenideElement elem = $(domElement);
        return elem;
    }

    // Unused alternative JS click implementation
    private void clickJSScaledCoords(int xClick, int yClick)
    {
        var scaledCoordForClicking = new ScaledCoord(xClick, yClick).scaleTo(1000, 1000);
        Selenide.executeJavaScript(
                                   "var x = arguments[0], y = arguments[1], done = arguments[arguments.length - 1];" +
                                   "var elem = document.elementFromPoint(x, y);" +
                                   "var evt = new MouseEvent('click', {clientX: x, clientY: y, bubbles: true});" +
                                   "elem.dispatchEvent(evt);" +
                                   "done();", // MUST call callback

                                   scaledCoordForClicking.x, scaledCoordForClicking.y);
    }

    /**
     * Record to handle coordinate scaling from the AI's relative space (usually 1000x1000) to the actual browser
     * viewport dimensions.
     */
    private record ScaledCoord(int x, int y)
    {
        ScaledCoord scaleTo(int width, int height)
        {
            JavascriptExecutor js = (JavascriptExecutor) Neodymium.getDriver();
            long innerWidth = (Long) js.executeScript("return window.innerWidth;");
            long innerHeight = (Long) js.executeScript("return window.innerHeight;");

            return new ScaledCoord((int) Math.round(((double) this.x / width) * innerWidth), (int) Math.round(((double) this.y / height) * innerHeight));
        }
    }

    /**
     * Invokes a method on the current test class instance via Reflection. Allows the AI to trigger specific Java helper
     * methods defined in subclasses. * @param methodName Name of the method to call.
     * 
     * @param parameterValue
     *            The single String parameter to pass.
     * @return The result of the method call, or null if failed.
     */
    public Object runMethodWithReflection(String methodName, String parameterValue)
    {
        Class<?> currentClass = this.getClass();

        try
        {
            Method method = currentClass.getMethod(methodName, String.class);
            Object result = method.invoke(this, parameterValue);
            return result;
        }
        catch (NoSuchMethodException e)
        {
            System.err.println("Error: Method '" + methodName + "' with a single String parameter not found.");
            e.printStackTrace();
        }
        catch (IllegalAccessException e)
        {
            System.err.println("Error: Cannot access method (e.g., it might be private or protected).");
            e.printStackTrace();
        }
        catch (InvocationTargetException e)
        {
            System.err.println("Error: The invoked method threw an exception.");
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Make sure we don't run out of tokens
     * 
     * @param client
     * @param history
     * @param modelName
     * @param functionDeclarations
     * @param systemPromot
     */
    private void manageHistory(Client client, List<Content> history, String modelName, List<FunctionDeclaration> functionDeclarations,
                                      Content systemPromot)
    {

        while (true)
        {
            CountTokensResponse response = client.models.countTokens(
                                                                     modelName,
                                                                     history,
                                                                     null);

            long currentTokens = response.totalTokens().get();
            System.out.println("Current Tokens: " + currentTokens + " / " + TOKEN_LIMIT);

            if (currentTokens <= TOKEN_LIMIT)
            {
                break;
            }

            // Prune logic
            if (history.size() > 3)
            {
                System.out.println("✂️ Pruning history...");
                history.remove(1); // Remove oldest Model response
                history.remove(1); // Remove oldest Tool response
            }
            else
            {
                System.err.println("⚠️ History cannot be pruned further.");
                break;
            }
        }
    }
}
