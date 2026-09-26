package dev.xr.rayneo.probe;

public final class ShellNavigationCheck {
    static void require(boolean ok){if(!ok)throw new AssertionError("route mismatch");}
    public static void main(String[] args){
        ShellNavigation n=new ShellNavigation();require(n.selected==ShellNavigation.NOW);
        n.selectMain(ShellNavigation.FUNCTIONS);n.open(0);n.open(ShellNavigation.RECORDS);n.open(ShellNavigation.DETAIL);
        require(n.back()&&n.selected==ShellNavigation.RECORDS);
        require(n.back()&&n.selected==0&&n.main==ShellNavigation.FUNCTIONS);
        require(n.back()&&n.selected==ShellNavigation.FUNCTIONS);
        n.open(1);n.selectMain(ShellNavigation.SETTINGS);n.open(11);n.open(4);
        require(n.back()&&n.selected==11);require(n.back()&&n.selected==ShellNavigation.SETTINGS);
        require(n.back()&&n.selected==ShellNavigation.NOW);require(!n.back());
        n.open(0);n.open(0);require(n.back()&&n.selected==ShellNavigation.NOW);
        n.selectMain(ShellNavigation.RECORDS);n.open(ShellNavigation.ANSWER);require(n.back()&&n.selected==ShellNavigation.RECORDS);
        boolean rejected=false;try{n.open(13);}catch(IllegalArgumentException e){rejected=true;}require(rejected);
        System.out.println("navigation passed");
    }
}
