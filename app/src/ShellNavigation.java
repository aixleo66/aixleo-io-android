package dev.xr.rayneo.probe;
import java.util.ArrayDeque;
/** UI-only route stack: navigation never owns business task lifetimes. */
final class ShellNavigation {
    static final int NOW=7, FUNCTIONS=8, RECORDS=9, SETTINGS=3, DETAIL=10, DEVICE=11, ANSWER=12;
    static final int[] MAIN={NOW,FUNCTIONS,RECORDS,SETTINGS};
    private final ArrayDeque<Integer> history=new ArrayDeque<>();
    int selected=NOW, main=NOW;
    boolean isMain(int page){for(int p:MAIN)if(p==page)return true;return false;}
    void selectMain(int page){if(!isMain(page))throw new IllegalArgumentException();history.clear();main=page;selected=page;}
    void open(int page){if(page<0||page>ANSWER)throw new IllegalArgumentException();if(page==selected)return;history.push(selected);selected=page;}
    boolean back(){if(!history.isEmpty()){selected=history.pop();return true;}if(selected!=NOW){selectMain(NOW);return true;}return false;}
}
