package org.tltv.gantt.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.timepedia.exporter.client.Export;
import org.timepedia.exporter.client.ExportConstructor;
import org.timepedia.exporter.client.ExportPackage;
import org.timepedia.exporter.client.Exportable;
import org.tltv.gantt.client.shared.AbstractStep;
import org.tltv.gantt.client.shared.GanttUtil;
import org.tltv.gantt.client.shared.Step;
import org.tltv.gantt.client.shared.SubStep;

import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.i18n.client.TimeZone;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.user.client.ui.Widget;
import com.vaadin.client.BrowserInfo;
import com.vaadin.client.MouseEventDetailsBuilder;
import com.vaadin.polymer.elemental.Function;

@Export
@ExportPackage(value = "gantt")
public class GanttElement implements Exportable, StepProvider {

    private static GanttElement instance;

    int previousHeight = -1;
    int previousWidth = -1;

    private GanttElementState state = null;
    private Map<Step, StepWidget> stepsMap = new HashMap<>();
    private Map<Step, Map<SubStep, SubStepWidget>> substepsMap = new HashMap<>();
    private Map<String, AbstractStep> uidMap = new HashMap<>();

    GanttWidget ganttWidget;

    LocaleDataProviderImpl localeDataProvider = new LocaleDataProviderImpl();

    StepHierarchyHandler internalHandler;

    GanttEventHandler ganttRpc = new GanttEventHandler() {

        @Override
        public void stepClicked(String stepUid, NativeEvent event, Element relativeToElement) {
            GanttMouseEventDetails details = GanttMouseEventDetails
                    .of(MouseEventDetailsBuilder.buildMouseEventDetails(event, relativeToElement));
            handleStepClickedEvent(getWidget().getGanttElement(), stepUid, details);
        }

        @Override
        public void onMove(String stepUid, String newStepUid, long startDate, long endDate, NativeEvent event,
                Element relativeToElement) {
            GanttMouseEventDetails details = GanttMouseEventDetails
                    .of(MouseEventDetailsBuilder.buildMouseEventDetails(event, relativeToElement));
            handleOnMoveEvent(getWidget().getGanttElement(), stepUid, newStepUid, startDate, endDate, details);
        }

        @Override
        public void onResize(String stepUid, long startDate, long endDate, NativeEvent event,
                Element relativeToElement) {
            GanttMouseEventDetails details = GanttMouseEventDetails
                    .of(MouseEventDetailsBuilder.buildMouseEventDetails(event, relativeToElement));
            handleOnResizeEvent(getWidget().getGanttElement(), stepUid, startDate, endDate, details);
        }

        @Override
        public boolean onStepRelationSelected(StepWidget source, boolean startingPointChanged,
                Element newRelationStepElement) {
            StepWidget sw = findStepWidgetByElement(newRelationStepElement);
            if (sw == null) {
                return false;
            }

            if (startingPointChanged) {
                // source is target (sw is related to source).
                // sw is new predecessor.

                if (sw.getStep().equals(source.getStep().getPredecessor())) {
                    return false;
                } else if (sw.getStep().equals(source.getStep())) {
                    // remove predecessor
                    handlePredecessorChangeEvent(getWidget().getGanttElement(), null, source.getStep().getUid(),
                            source.getStep().getUid());
                    return true;
                }
                handlePredecessorChangeEvent(getWidget().getGanttElement(), sw.getStep().getUid(),
                        source.getStep().getUid(), null);
            } else {
                // source is original target (sw is new target)

                if (sw.getStep().equals(source.getStep())) {
                    return false;
                } else if (sw.getStep().equals(source.getStep().getPredecessor())) {
                    // remove predecessor
                    handlePredecessorChangeEvent(getWidget().getGanttElement(), null, source.getStep().getUid(),
                            source.getStep().getUid());
                    return true;
                }

                if (source.getStep().getPredecessor() != null) {
                    StepWidget w = getStepWidget(source.getStep().getPredecessor());
                    if (w.getStep() != null && w.getStep().getPredecessor() != null
                            && w.getStep().getPredecessor().equals(sw.getStep())) {
                        // there's relation already, with different direction.
                        return false;
                    }
                }
                handlePredecessorChangeEvent(getWidget().getGanttElement(), source.getStep().getPredecessor().getUid(),
                        sw.getStep().getUid(), source.getStep().getUid());
            }
            return true;
        }

        @Override
        public void requestLayout() {
            notifySizeChanged();
        }
    };

    public static native void handleStepClickedEvent(Element elem, String stepUid, GanttMouseEventDetails details)
    /*-{
        elem.handleStepClicked(stepUid, details);
    }-*/;

    public static native void handleOnMoveEvent(Element elem, String stepUid, String newStepUid, double startDate,
            double endDate, GanttMouseEventDetails details)
    /*-{
        elem.handleOnMove(stepUid, newStepUid, startDate, endDate, details);
    }-*/;

    public static native void handleOnResizeEvent(Element elem, String stepUid, double startDate, double endDate,
            GanttMouseEventDetails details)
    /*-{
        elem.handleOnResize(stepUid, startDate, endDate, details);
    }-*/;

    public static native void handlePredecessorChangeEvent(Element elem, String newPredecessorStepUid,
            String forTargetStepUid, String clearPredecessorForStepUid)
    /*-{
        if(newPredecessorStepUid == forTargetStepUid) {
            return;
        }
        elem.handleOnPredecessorChange(newPredecessorStepUid, forTargetStepUid, clearPredecessorForStepUid);
    }-*/;

    @ExportConstructor
    public static GanttElement constructor() {
        if (instance == null) {
            instance = new GanttElement();
            instance.init(null);
        }
        return instance;
    }

    @ExportConstructor
    public static GanttElement constructor(JavaScriptObject jselement) {
        if (instance == null) {
            instance = new GanttElement();
            instance.init(jselement);
        }
        return instance;
    }

    private GanttElement() {
    }

    protected void init(JavaScriptObject jselement) {
        createWidget(jselement);

        getWidget().ready(new Function<Object, Object>() {
            @Override
            public Object call(Object args) {
                doInitElement();
                return null;
            }
        });
    }

    protected Widget createWidget(JavaScriptObject jselement) {
        if (ganttWidget == null) {
            ganttWidget = (jselement != null) ? new GanttWidget(jselement) : new GanttWidget();
            internalHandler = new StepHierarchyHandler(ganttWidget);
        }
        return ganttWidget;
    }

    public GanttWidget getWidget() {
        return ganttWidget;
    }

    public Element getGanttElement() {
        return getWidget().getGanttElement();
    }

    void doInitElement() {
        BrowserInfo info = BrowserInfo.get();
        getWidget().setBrowserInfo(info.isIE(), info.isChrome(), info.isSafari(), info.isWebkit(),
                info.getBrowserMajorVersion());
        getWidget().setAlwaysCalculatePixelWidths(info.isSafari() || info.isOpera());
        getWidget().setTouchSupported(info.isTouchDevice());
        getWidget().initWidget(ganttRpc, localeDataProvider);
        getWidget().notifyHeightChanged((int) GanttUtil.getBoundingClientRectHeight(getWidget().getElement()));
        getWidget().notifyWidthChanged((int) GanttUtil.getBoundingClientRectWidth(getWidget().getElement()));
    }

    public static native JavaScriptObject[] getSteps(com.google.gwt.dom.client.Element elem)
    /*-{
        return elem.steps;
    }-*/;

    private List<JavaScriptObject> getStepsList(com.google.gwt.dom.client.Element elem) {
        JavaScriptObject[] arr = getSteps(elem);
        if (arr == null || arr.length == 0) {
            return new ArrayList<>();
        }
        return Arrays.asList(arr);
    }

    private List<Step> getSteps() {
        List<JavaScriptObject> l = getStepsList(getWidget().getGanttElement());
        return l != null ? toSteps(l) : new ArrayList<>();
    }

    protected List<StepWidget> getStepWidgets() {
        List<StepWidget> list = new ArrayList<StepWidget>();
        for (Step s : getSteps()) {
            if (stepsMap.containsKey(s)) {
                list.add(stepsMap.get(s));
            }
        }
        return list;
    }

    protected StepWidget findStepWidgetByElement(Element target) {
        List<StepWidget> stepWidgets = getStepWidgets();
        for (StepWidget w : stepWidgets) {
            if (isOrHasChild(w, target)) {
                return w;
            }
        }
        for (StepWidget w : stepWidgets) {
            for (SubStepWidget sw : w.getSubSteps()) {
                if (isOrHasChild(sw, target)) {
                    return w;
                }
            }
        }
        return null;
    }

    private boolean isOrHasChild(AbstractStepWidget widget, Element target) {
        return widget.getElement().isOrHasChild(target) || widget.getShadowRoot().isOrHasChild(target);
    }

    protected Map<Step, StepWidget> getStepsMap() {
        Map<Step, StepWidget> map = new HashMap<>();
        map.putAll(stepsMap);
        return map;
    }

    public JavaScriptObject getStepByUid(String uid) {
        if (uidMap.containsKey(uid)) {
            return uidMap.get(uid).toJson().getJavaScriptObject();
        }
        return null;
    }

    @Override
    public StepWidget getStepWidget(Step target) {
        return stepsMap.get(target);
    }

    public SubStepWidget getSubStepWidget(SubStep target) {
        Map<SubStep, SubStepWidget> m = substepsMap.get(target.getOwner());
        if (m != null) {
            return m.get(target);
        }
        return null;
    }

    /** Updates all steps predecessor visualizations. */
    public void updateAllStepsPredecessors() {
        for (StepWidget s : getStepWidgets()) {
            s.updatePredecessor(true);
        }
    }

    public void setState(JavaScriptObject newState) {
        setState(newState, null);
    }

    public void setState(JavaScriptObject newState, JavaScriptObject[] newStepsJson) {
        doSetState(GanttElementState.toState(newState), newStepsJson);
    }

    private void doSetState(GanttElementState newState, JavaScriptObject[] newStepsJson) {
        getWidget().ready(new Function<Object, Object>() {
            @Override
            public Object call(Object args) {
                boolean initialState = state == null;
                state = newState;
                localeDataProvider.localeProvider = state;
                List<Step> steps = null;
                if (newStepsJson != null) {
                    steps = new ArrayList<>();
                    steps.addAll(toSteps(Arrays.asList(newStepsJson)));
                }
                setLocale(state.locale);
                if (state.timeZoneJson != null) {
                    setTimeZoneId(state.timeZoneId, state.timeZoneJson);
                } else {
                    setTimeZoneId(state.timeZoneId);
                }

                getWidget().setResolution(state.resolution);
                getWidget().setStartDate(Long.valueOf(state.getStartDate()));
                getWidget().setEndDate(Long.valueOf(state.getEndDate()));
                getWidget().setFirstDayOfRange(state.firstDayOfRange);
                getWidget().setFirstHourOfRange(state.firstHourOfRange);
                getWidget().setMovableSteps(!state.readOnly && state.movableSteps);
                getWidget().setResizableSteps(!state.readOnly && state.resizableSteps);
                getWidget().setMovableStepsBetweenRows(state.movableStepsBetweenRows);
                getWidget().setMonthRowVisible(state.monthRowVisible);
                getWidget().setYearRowVisible(state.yearRowVisible);
                getWidget().setBackgroundGridEnabled(state.backgroundGridEnabled);
                getWidget().setWeekFormat(state.weekFormat);
                getWidget().setDayFormat(state.dayFormat);
                getWidget().setMonthFormat(state.monthFormat);
                getWidget().setYearFormat(state.yearFormat);
                getWidget().setDefaultContextMenuEnabled(state.defaultContextMenuEnabled);

                getWidget().setForceUpdateTimeline();
                getWidget().resetListeners();

                // need to call onAttach explicitly
                if (!getWidget().isAttached()) {
                    getWidget().onAttach();
                }

                if (steps != null) {
                    doSetSteps(steps);
                }

                Scheduler.get().scheduleDeferred(new ScheduledCommand() {

                    @Override
                    public void execute() {
                        getWidget().requestUpdate(getStepWidgets());
                        getWidget().notifyHeightChanged(
                                (int) GanttUtil.getBoundingClientRectHeight(getWidget().getElement()));
                        getWidget().notifyWidthChanged(
                                (int) GanttUtil.getBoundingClientRectWidth(getWidget().getElement()));
                        updateAllStepsPredecessors();
                    }
                });

                return null;
            }
        });
    }

    /** Set new list of steps. */
    public void setSteps(List<JavaScriptObject> newSteps) {
        getWidget().ready(new Function<Object, Object>() {
            @Override
            public Object call(Object args) {
                doSetSteps(toSteps(newSteps));
                return null;
            }
        });
    }

    /**
     * Add step to given index. Shifts existing steps forward.
     */
    public void addStep(JavaScriptObject step, int index) {
        Step s = Step.toStep(step);
        doAddStep(s, index);
        doUpdateStep(s);
        deferredUpdate(Arrays.asList(s));
    }

    /**
     * Add steps starting from given index. Shifts existing steps forward.
     */
    public void addSteps(List<JavaScriptObject> steps, int fromIndex) {
        List<Step> stepList = toSteps(steps);
        doAddSteps(stepList, fromIndex);
        deferredUpdate(stepList);
    }

    private void doAddSteps(List<Step> steps, int fromIndex) {
        for (StepWidget sw : getStepWidgets()) {
            // clearing calculated height will make sure that all predecessors
            // are recalculated right.
            sw.clearCalculatedHeight();
        }
        int index = Math.max(0, fromIndex);
        for (Step s : steps) {
            doAddStep(s, index++);
        }
        for (Step s : steps) {
            doUpdateStep(s);
        }
    }

    private void doAddStep(Step s, int index) {
        doAddStep(null, s, index, true);
    }

    private void doAddStep(JavaScriptObject element, Step s, int index, boolean updateAffectedSteps) {
        StepWidget stepWidget = stepsMap.get(s);
        if (stepWidget == null) {
            stepWidget = (element == null) ? new StepWidget() : new StepWidget(element);
            stepWidget.setGantt(getWidget(), localeDataProvider);
            stepsMap.put(s, stepWidget);
            uidMap.put(s.getUid(), s);
        }
        getWidget().insertStep(index, stepWidget);
        // updateAffectedSteps set to true will update position for all steps
        // below. It may be heavy operation depending on how may steps there
        // are.
        getWidget().setStep(index, stepWidget, updateAffectedSteps);

        stepWidget.setReadOnly(state.readOnly);
        Step predecessor = s.getPredecessor();
        if (predecessor != null) {
            stepWidget.setPredecessorStepWidget(stepsMap.get(predecessor));
        } else {
            stepWidget.setPredecessorStepWidget(null);
        }
    }

    /**
     * Remove steps and all sub steps in them.
     */
    public void removeSteps(List<JavaScriptObject> steps) {
        List<Step> remSteps = new ArrayList<>();
        for (JavaScriptObject o : steps) {
            remSteps.add(Step.toStep(o));
        }
        doRemoveSteps(remSteps);
    }

    /**
     * Remove step and its sub steps.
     */
    public void removeStep(JavaScriptObject step) {
        removeSteps(Arrays.asList(step));
    }

    private void doRemoveSteps(List<Step> steps) {
        for (Step s : steps) {
            if (stepsMap.containsKey(s)) {
                StepWidget stepWidget = stepsMap.get(s);
                internalHandler.requestRemoveStep(stepWidget);
                stepsMap.remove(stepWidget.getStep());
                uidMap.remove(s.getUid());
                internalHandler.updateRelatedStepsPredecessors(stepWidget, s, true, getStepWidgets());
            }
        }
    }

    private List<Step> toSteps(List<JavaScriptObject> newStepObjects) {
        List<Step> newSteps = new ArrayList<>();
        for (JavaScriptObject o : newStepObjects) {
            newSteps.add(Step.toStep(o));
        }
        return newSteps;
    }

    private void doSetSteps(List<Step> newSteps) {
        // Here we handle removing and other necessary changed related
        // hierarchy. remove old steps
        List<Step> remSteps = new ArrayList<>();
        for (Step s : getSteps()) {
            if (!newSteps.contains(s)) {
                remSteps.add(s);
            }
        }
        doRemoveSteps(remSteps);

        // Sync steps with changed hierarchy; add new ones and move
        // existing ones.
        doAddSteps(newSteps, 0);
    }

    private void doSetSubSteps(Step step) {

        List<SubStep> oldSubSteps = new ArrayList<>();
        if (substepsMap.containsKey(step)) {
            oldSubSteps.addAll(substepsMap.get(step).keySet());
        }

        // remove old steps
        for (SubStep s : oldSubSteps) {
            if (!step.getSubSteps().contains(s)) {
                SubStepWidget w = getSubStepWidget(s);
                if (w != null) {
                    getStepWidget(step).remove(w);
                    substepsMap.get(step).remove(s);
                    uidMap.remove(s.getUid());
                }
            }
        }

        for (SubStep substep : step.getSubSteps()) {
            doAddUpdateSubStep(substep);
        }
    }

    private void doAddUpdateSubStep(final SubStep substep) {
        if (substep.getOwner() == null) {
            return;
        }
        StepWidget owner = getStepWidget(substep.getOwner());
        if (owner == null) {
            return;
        }
        final boolean added;
        SubStepWidget w = getSubStepWidget(substep);
        if (w == null) {
            w = new SubStepWidget();
            w.setGantt(getWidget(), localeDataProvider);
            w.setOwner(owner);
            Map<SubStep, SubStepWidget> m = substepsMap.get(substep.getOwner());
            if (m == null) {
                m = new HashMap<>();
                substepsMap.put(substep.getOwner(), m);
            }
            m.put(substep, w);
            uidMap.put(substep.getUid(), substep);
            owner.add(w);
            added = true;
        } else {
            added = false;
        }

        doUpdateSubStep(substep, added);
    }

    private void doUpdateSubStep(SubStep substep, boolean added) {
        SubStepWidget w = getSubStepWidget(substep);
        w.getBar().setAttribute("background-color", substep.getBackgroundColor());
        w.getBar().setAttribute("caption", substep.getCaption());
        w.setStep(substep);
        w.setReadOnly(state.readOnly);
        if (added) {
            w.getOwner().updateStylesForSubSteps();
        }
        w.getElement().setAttribute("start-date", "" + substep.getStartDate());
        w.getElement().setAttribute("end-date", "" + substep.getEndDate());
        w.updateWidth();
    }

    private void doUpdateStep(Step s) {
        StepWidget w = getStepWidget(s);
        uidMap.put(s.getUid(), s);
        stepsMap.put(s, w);
        w.updatePredecessorWidgetReference(s, this);
        // need to be called before setStep
        w.getBar().setAttribute("background-color", s.getBackgroundColor());
        w.getBar().setAttribute("caption", s.getCaption());
        w.setStep(s);
        if (!w.getElement().hasParentElement()) {
            getWidget().insertStep(getStepIndex(s), w);
            getWidget().setStep(getStepIndex(s), w, true);
        }
        w.getBar().setAttribute("start-date", "" + s.getStartDate());
        w.getBar().setAttribute("end-date", "" + s.getEndDate());
        doSetSubSteps(s);
        w.updateWidth();

        w.updatePredecessor(false);
        internalHandler.updateRelatedStepsPredecessors(w, s, false, getStepWidgets());
    }

    /**
     * Calls getWidget().update(List<Step>).
     */
    private void deferredUpdate(List<Step> steps) {
        Scheduler.get().scheduleDeferred(new ScheduledCommand() {
            @Override
            public void execute() {
                List<StepWidget> stepWidgets = new ArrayList<>();
                for (Step s : steps) {
                    stepWidgets.add(getStepWidget(s));
                }
                getWidget().update(stepWidgets);
            }
        });
    }

    /**
     * Update given step with sub steps or add/update given sub step.
     */
    public void update(JavaScriptObject stepOrSubstep) {
        if (stepOrSubstep == null) {
            return;
        }
        JSONObject json = new JSONObject(stepOrSubstep);
        if (json.containsKey("substep") && json.get("substep").isBoolean().booleanValue()) {
            updateSubStep(stepOrSubstep);
        } else {
            updateStep(stepOrSubstep);
        }
    }

    /**
     * Update step and its sub steps.
     */
    public void updateStep(JavaScriptObject step) {
        doUpdateStep(Step.toStep(step));
    }

    /**
     * Update existing sub step and add non-existing.
     */
    public void updateSubStep(JavaScriptObject substep) {
        SubStep sub = SubStep.toStep(substep);
        SubStepWidget w = getSubStepWidget(sub);
        if (w == null) {
            doAddUpdateSubStep(sub);
        } else {
            doUpdateSubStep(sub, false);
        }
    }

    protected int getStepIndex(Step s) {
        return Math.max(0, getSteps().indexOf(s));
    }

    private void deferredUpdateAllStepsPredecessors() {
        Scheduler.get().scheduleDeferred(new ScheduledCommand() {
            @Override
            public void execute() {
                internalHandler.updateAllStepsPredecessors(getStepWidgets());
            }
        });
    }

    public void setLocale(String locale) {
        state.locale = locale;
        localeDataProvider.locale = locale;
        localeDataProvider.dateTimeService = null;
    }

    public void setTimeZoneId(String timezoneId) {
        state.timeZoneId = timezoneId;
        localeDataProvider.timeZoneId = timezoneId;
        localeDataProvider.timeZone = TimeZone.createTimeZone(0);
    }

    public void setTimeZoneId(String timezoneId, String timeZoneJson) {
        state.timeZoneId = timezoneId;
        state.timeZoneJson = timeZoneJson;
        localeDataProvider.timeZoneId = timezoneId;
        localeDataProvider.timeZone = TimeZone.createTimeZone(timeZoneJson);
    }

    public void notifySizeChanged() {
        notifyHeightChanged(false);
        notifyWidthChanged(false);
    }

    public void notifyWidthChanged(boolean force) {
        final int width = getWidget().getElement().getClientWidth();
        if (previousWidth != width) {
            previousWidth = width;
            Scheduler.get().scheduleDeferred(new ScheduledCommand() {

                @Override
                public void execute() {
                    getWidget().notifyWidthChanged(width);
                    updateAllStepsPredecessors();
                }
            });
        }
    }

    public void notifyHeightChanged(boolean force) {
        final int height = getWidget().getElement().getClientHeight();
        if (force || previousHeight != height) {
            previousHeight = height;

            Scheduler.get().scheduleDeferred(new ScheduledCommand() {

                @Override
                public void execute() {
                    getWidget().notifyHeightChanged(height);
                }
            });
        }
    }

    /**
     * Registers given gantt-step element. This will initialize the element
     * properly.
     */
    public void registerStepElement(JavaScriptObject stepElementObj, JavaScriptObject step) {
        if (step == null) {
            return;
        }
        Step s = Step.toStep(step);
        doAddStep(stepElementObj, s, getStepIndex(s), false);
        doUpdateStep(s);
    }
}
