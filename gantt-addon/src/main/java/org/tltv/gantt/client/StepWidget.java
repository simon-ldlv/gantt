/*
 * Copyright 2014 Tomi Virtanen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tltv.gantt.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.tltv.gantt.client.ArrowElement.ArrowChangeHandler;
import org.tltv.gantt.client.shared.GanttUtil;
import org.tltv.gantt.client.shared.Step;

import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.RepeatingCommand;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.core.shared.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.ui.Widget;
import com.vaadin.polymer.elemental.Function;

/**
 * Widget representing a one Step in the Gantt chart.
 *
 * @author Tltv
 *
 */
public class StepWidget extends AbstractStepWidget {

    public static final String STYLE_HAS_SUB_STEPS = "has-sub-steps";

    private StepWidget predecessorStepWidget;

    private ArrowElement predecessorArrow;

    private ArrowChangeHandler arrowChangeHandler = new ArrowChangeHandler() {

        @Override
        public boolean onArrowChanged(boolean startingPointChanged, NativeEvent event) {
            Element target = GanttUtil.getElementFromPoint(getRootElement(gantt.getElement()),
                    GanttUtil.getTouchOrMouseClientX(event), GanttUtil.getTouchOrMouseClientY(event));
            if (target != null) {
                return gantt.getRpc().onStepRelationSelected(StepWidget.this, startingPointChanged, target);
            }
            return false;
        }
    };

    @Override
    protected void onDetach() {
        if (gantt != null && predecessorArrow != null) {
            gantt.unregisterContentElement((Widget) predecessorArrow);
        }
        super.onDetach();
    }

    public StepWidget(JavaScriptObject jselement) {
        super(jselement);
    }

    public StepWidget() {
        super();
    }

    @Override
    public Step getStep() {
        return (Step) super.getStep();
    }

    public StepWidget getPredecessorStepWidget() {
        return predecessorStepWidget;
    }

    public void setPredecessorStepWidget(StepWidget predecessorStepWidget) {
        StepWidget.this.predecessorStepWidget = predecessorStepWidget;
    }

    public void updateRelatedPredecessor(StepWidget sourceStepWidget, final Step sourceRelatedStep, boolean removed) {
        if (getStep() != null && sourceRelatedStep.equals(getStep().getPredecessor())) {
            if (removed) {
                predecessorStepWidget = null;
            } else if (sourceStepWidget != null) {
                predecessorStepWidget = sourceStepWidget;
            }
            updatePredecessor(false);
        }
    }

    public void updatePredecessor(boolean deferred) {
        createPredecessorElements();

        if (predecessorStepWidget == null && !deferred) {
            return;
        }

        if (deferred) {
            drawArrowDeferred();
        } else {
            drawArrow();
        }
    }

    private void drawArrowDeferred() {
        Scheduler.get().scheduleDeferred(new ScheduledCommand() {
            @Override
            public void execute() {
                drawArrow();
            }
        });
    }

    /* Draw arrow. return true, if redraw is not needed. */
    private void drawArrow() {
        StepWidget predecessorStep = getPredecessorStepWidget();
        if (predecessorStep == null) {
            GWT.log("Predecessor step widget is null. StepWidget.drawArrow() can't draw arrow.");
            return;
        }
        if (!isSet() || !predecessorStep.isSet()) {
            Scheduler.get().scheduleFixedDelay(new RepeatingCommand() {
                @Override
                public boolean execute() {
                    drawArrow();
                    return false;
                }
            }, 50);
            return;
        }
        
        ArrowPositionData data = new ArrowPositionData(getPredecessorStepWidget().getElement(),
                getBar());

        predecessorArrow.setWidth(data.getWidth());
        predecessorArrow.setHeight(data.getHeight());
        predecessorArrow.setTop((int) data.getTop());
        predecessorArrow.setLeft((int) data.getLeft());

        predecessorArrow.draw(data);
    }

    public ArrowElement createArrowWidget() {
        SvgArrowWidget a = new SvgArrowWidget();
        a.ready(new Function<Object, Object>() {
            @Override
            public Object call(Object args) {
                a.setReadOnly(isReadOnly());
                return null;
            }
        });
        return a;
    }

    protected void createPredecessorElements() {
        if (predecessorStepWidget == null) {
            if (predecessorArrow != null) {
                gantt.unregisterContentElement((Widget) predecessorArrow);
            }
        } else {
            if (predecessorArrow == null) {
                predecessorArrow = createArrowWidget();
                predecessorArrow.whenReady(new Function<Object, Object>() {
                    @Override
                    public Object call(Object arg) {
                        predecessorArrow.setUpEventHandlers(gantt.isTouchSupported(), gantt.isMsTouchSupported());
                        predecessorArrow.setArrowChangeHandler(arrowChangeHandler);
                        return null;
                    }
                });
            }

            gantt.registerContentElement((Widget) predecessorArrow);
        }
    }

    public Widget getPredecessorArrowWidget() {
        return (Widget) predecessorArrow;
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        if (predecessorArrow != null) {
            predecessorArrow.setReadOnly(readOnly);
        }
    }

    public GanttWidget getGanttWidget() {
        return gantt;
    }

    public List<SubStepWidget> getSubSteps() {
        List<SubStepWidget> list = new ArrayList<SubStepWidget>();
        Widget widget;
        Iterator<Widget> iterator = iterator();
        while (iterator.hasNext()) {
            widget = iterator.next();
            if (widget instanceof SubStepWidget) {
                list.add((SubStepWidget) widget);
            }
        }
        return list;
    }

    public String getStepUidBySubStepElement(Element element) {
        SubStepWidget w = getSubStepWidgetByElement(element);
        if (w != null) {
            return w.getStep().getUid();
        }
        return null;
    }

    public SubStepWidget getSubStepWidgetByElement(Element element) {
        Widget w = getWidget(DOM.getChildIndex(getShadowRoot(), element) - countNonSubStepChilds() - 1);
        if (w instanceof SubStepWidget) {
            return (SubStepWidget) w;
        }
        return null;
    }

    @Override
    public void updateWidth() {
        super.updateWidth();

        List<SubStepWidget> subSteps = getSubSteps();
        updateStylesForSubSteps(!subSteps.isEmpty());

        for (SubStepWidget subStep : subSteps) {
            subStep.updateWidth();
        }
    }

    private void updateStylesForSubSteps(boolean hasSubSteps) {
        if (!hasSubSteps) {
            getBar().removeClassName(STYLE_HAS_SUB_STEPS);
        } else {
            getBar().addClassName(STYLE_HAS_SUB_STEPS);
        }
    }

    public void updateStylesForSubSteps() {
        List<SubStepWidget> subSteps = getSubSteps();
        updateStylesForSubSteps(!subSteps.isEmpty());
    }

    public void updatePredecessorWidgetReference(Step step, StepProvider stepProvider) {
        Step predecessor = step.getPredecessor();
        if (predecessor != null) {
            setPredecessorStepWidget(stepProvider.getStepWidget(predecessor));
        } else {
            setPredecessorStepWidget(null);
        }
    }
}
