// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.paint

import com.intellij.ui.JBColor
import com.intellij.util.SmartList
import com.intellij.vcs.log.graph.EdgePrintElement
import com.intellij.vcs.log.graph.NodePrintElement
import com.intellij.vcs.log.graph.PrintElement
import com.intellij.vcs.log.graph.impl.print.elements.TerminalEdgePrintElement
import java.awt.*
import java.awt.geom.Ellipse2D
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * @author erokhins
 */
internal open class SimpleGraphCellPainter(private val colorGenerator: ColorGenerator) : GraphCellPainter {
  protected open val rowHeight: Int get() = PaintParameters.ROW_HEIGHT

  override fun paint(g2: Graphics2D, printElements: Collection<PrintElement>) {
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val painter = MyPainter(rowHeight)
    val selected: MutableList<PrintElement> = SmartList()
    for (printElement in printElements) {
      if (printElement.isSelected) {
        selected.add(printElement) // to draw later
      }
      else {
        painter.paintElement(g2, printElement, colorGenerator.getColor(printElement.colorId), false)
      }
    }

    // draw selected elements
    for (printElement in selected) {
      painter.paintElement(g2, printElement, MARK_COLOR, true)
    }

    for (printElement in selected) {
      painter.paintElement(g2, printElement, colorGenerator.getColor(printElement.colorId), false)
    }
  }

  override fun getElementUnderCursor(printElements: Collection<PrintElement>, x: Int, y: Int): PrintElement? {
    val elementWidth = PaintParameters.getElementWidth(rowHeight)
    val circleRadius = PaintParameters.getCircleRadius(rowHeight)
    for (printElement in printElements) {
      if (printElement is NodePrintElement) {
        if (isOverNode(printElement.positionInCurrentRow, x, y, rowHeight, elementWidth, circleRadius)) {
          return printElement
        }
      }
    }

    val lineThickness = PaintParameters.getLineThickness(rowHeight)
    for (printElement in printElements) {
      if (printElement is EdgePrintElement) {
        if (isOverEdge(printElement.positionInCurrentRow, printElement.positionInOtherRow, printElement.type, x, y, rowHeight, elementWidth, lineThickness)) {
          return printElement
        }
      }
    }
    return null
  }

  private fun isOverEdge(position: Int, otherPosition: Int, edgeType: EdgePrintElement.Type, x: Int, y: Int,
                         rowHeight: Int, elementWidth: Int, lineThickness: Float): Boolean {
    val x1 = elementWidth * position + elementWidth / 2
    val y1 = rowHeight / 2
    val x2 = elementWidth * otherPosition + elementWidth / 2
    val y2 = if (edgeType == EdgePrintElement.Type.DOWN) rowHeight + rowHeight / 2 else -rowHeight / 2
    return distance(x1, y1, x, y) + distance(x2, y2, x, y) < distance(x1, y1, x2, y2) + lineThickness
  }

  private fun isOverNode(position: Int, x: Int, y: Int, rowHeight: Int, elementWidth: Int, circleRadius: Int): Boolean {
    val x0 = elementWidth * position + elementWidth / 2
    val y0 = rowHeight / 2
    return distance(x0, y0, x, y) <= circleRadius
  }

  private class MyPainter(private val rowHeight: Int) {
    private val elementWidth = PaintParameters.getElementWidth(rowHeight)

    private val circleRadius = PaintParameters.getCircleRadius(rowHeight)
    private val lineThickness = PaintParameters.getLineThickness(rowHeight)

    private val selectedCircleRadius = PaintParameters.getSelectedCircleRadius(rowHeight)
    private val selectedLineThickness = PaintParameters.getSelectedLineThickness(rowHeight)

    private val ordinaryStroke = BasicStroke(lineThickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_BEVEL)
    private val selectedStroke = BasicStroke(selectedLineThickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_BEVEL)

    private fun getDashedStroke(dash: FloatArray): Stroke {
      return BasicStroke(lineThickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_BEVEL, 0f, dash,
                         dash[0] / 2)
    }

    private fun getSelectedDashedStroke(dash: FloatArray): Stroke {
      return BasicStroke(selectedLineThickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_BEVEL, 0f, dash,
                         dash[0] / 2)
    }

    private fun paintLine(g2: Graphics2D, x1: Int, y1: Int, x2: Int, y2: Int, startArrowX: Int, startArrowY: Int,
                          hasArrow: Boolean, isUsual: Boolean, isSelected: Boolean) {
      g2.stroke = if (isUsual || hasArrow) {
        if (isSelected) selectedStroke else ordinaryStroke
      }
      else {
        val edgeLength = if (x1 == x2) rowHeight.toDouble() else hypot((x1 - x2).toDouble(), (y1 - y2).toDouble())
        val dashLength = getDashLength(edgeLength, rowHeight)
        if (isSelected) getSelectedDashedStroke(dashLength) else getDashedStroke(dashLength)
      }

      g2.drawLine(x1, y1, x2, y2)
      if (hasArrow) {
        val (endArrowX1, endArrowY1) = rotate(x1.toDouble(), y1.toDouble(), startArrowX.toDouble(), startArrowY.toDouble(),
                                              sqrt(ARROW_ANGLE_COS2), sqrt(1 - ARROW_ANGLE_COS2),
                                              ARROW_LENGTH * rowHeight)
        val (endArrowX2, endArrowY2) = rotate(x1.toDouble(), y1.toDouble(), startArrowX.toDouble(), startArrowY.toDouble(),
                                              sqrt(ARROW_ANGLE_COS2), -sqrt(1 - ARROW_ANGLE_COS2),
                                              ARROW_LENGTH * rowHeight)
        g2.drawLine(startArrowX, startArrowY, endArrowX1, endArrowY1)
        g2.drawLine(startArrowX, startArrowY, endArrowX2, endArrowY2)
      }
    }

    private fun paintEdge(g2: Graphics2D, edge: EdgePrintElement, isSelected: Boolean) {
      val isDown = edge.type == EdgePrintElement.Type.DOWN
      val isUsual = edge.lineStyle == EdgePrintElement.LineStyle.SOLID
      val hasArrow = edge.hasArrow()
      val isTerminal = edge is TerminalEdgePrintElement

      val from = edge.positionInCurrentRow
      val to = edge.positionInOtherRow

      val elementWidth = PaintParameters.getElementWidth(rowHeight)
      val x1 = elementWidth * from + elementWidth / 2
      val y1 = rowHeight / 2
      if (from == to) {
        val arrowGap = if (isTerminal) PaintParameters.getCircleRadius(rowHeight) / 2 + 1 else 0
        val y2 = if (isDown) rowHeight - arrowGap else arrowGap
        paintLine(g2, x1, y1, x1, y2, x1, y2, hasArrow, isUsual, isSelected)
      }
      else {
        assert(!isTerminal)
        // paint non-vertical lines twice the size to make them dock with each other well
        val x2 = elementWidth * to + elementWidth / 2
        val y2 = if (isDown) rowHeight + rowHeight / 2 else -rowHeight / 2
        paintLine(g2, x1, y1, x2, y2, (x1 + x2) / 2, (y1 + y2) / 2, hasArrow, isUsual, isSelected)
      }
    }

    private fun paintCircle(g2: Graphics2D, position: Int, isSelected: Boolean) {
      val x0 = elementWidth * position + elementWidth / 2
      val y0 = rowHeight / 2
      val r = if (isSelected) selectedCircleRadius else circleRadius

      val circle = Ellipse2D.Double(x0 - r + 0.5, y0 - r + 0.5, (2 * r).toDouble(), (2 * r).toDouble())
      g2.fill(circle)
    }

    fun paintElement(g2: Graphics2D, element: PrintElement, color: Color, isSelected: Boolean) {
      g2.color = color
      when (element) {
        is EdgePrintElement -> paintEdge(g2, element, isSelected)
        is NodePrintElement -> paintCircle(g2, element.positionInCurrentRow, isSelected)
      }
    }
  }

  companion object {
    private val MARK_COLOR: Color = JBColor.BLACK
    private const val ARROW_ANGLE_COS2 = 0.7
    private const val ARROW_LENGTH = 0.3

    private fun rotate(x: Double, y: Double, centerX: Double, centerY: Double, cos: Double, sin: Double,
                       arrowLength: Double): Pair<Int, Int> {
      val translateX = (x - centerX)
      val translateY = (y - centerY)

      val d = hypot(translateX, translateY)
      val scaleX = arrowLength * translateX / d
      val scaleY = arrowLength * translateY / d

      val rotateX = scaleX * cos - scaleY * sin
      val rotateY = scaleX * sin + scaleY * cos

      return Pair(Math.round(rotateX + centerX).toInt(), Math.round(rotateY + centerY).toInt())
    }

    private fun getDashLength(edgeLength: Double, rowHeight: Int): FloatArray {
      // If the edge is vertical, then edgeLength is equal to rowHeight. Exactly one dash and one space fits on the edge,
      // so spaceLength + dashLength is also equal to rowHeight.
      // When the edge is not vertical, spaceLength is kept the same, but dashLength is chosen to be slightly greater
      // so that the whole number of dashes would fit on the edge.

      val dashCount = max(1, floor(edgeLength / rowHeight).toInt())
      val spaceLength = rowHeight / 2.0f - 2
      val dashLength = (edgeLength / dashCount - spaceLength).toFloat()
      return floatArrayOf(dashLength, spaceLength)
    }

    private fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Double {
      return hypot((x1 - x2).toDouble(), (y1 - y2).toDouble())
    }
  }
}
