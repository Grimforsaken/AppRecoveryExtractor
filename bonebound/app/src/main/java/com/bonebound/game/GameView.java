package com.bonebound.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Iterator;

public class GameView extends View {
    enum Mode { SELECT, PLAY, DEAD, WIN }
    enum Loadout { SWORD, AXE, BOW, CROSSBOW, SUPPORT }

    static final float WORLD = 4000f, GRAVITY = 1700f;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF attackBtn = new RectF();
    final RectF[] choices = {new RectF(),new RectF(),new RectF(),new RectF(),new RectF()};
    final String[] names = {"SWORD","AXE","BOW","CROSSBOW","SUPPORT"};
    final ArrayList<Enemy> enemies = new ArrayList<>();
    final ArrayList<Shot> shots = new ArrayList<>();

    Mode mode = Mode.SELECT;
    Loadout loadout;
    Actor player, companion;
    float camera;
    long last = SystemClock.uptimeMillis();

    int joyPointer=-1, attackPointer=-1;
    float joyStartX, joyStartY, joyX, joyY;
    boolean jumpLatch;
    boolean wand, staff;
    float supportCd;

    public GameView(Context context) {
        super(context);
        setKeepScreenOn(true);
        setFocusable(true);
    }

    float ground() { return getHeight() * .79f; }
    static float clamp(float v,float a,float b){ return Math.max(a,Math.min(b,v)); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        long now=SystemClock.uptimeMillis();
        float dt=Math.min(.033f,(now-last)/1000f);
        last=now;
        if(mode==Mode.PLAY) update(dt);
        drawScene(c);
        if(mode==Mode.SELECT) drawSelect(c); else drawGame(c);
        postInvalidateOnAnimation();
    }

    void start(Loadout l){
        loadout=l; mode=Mode.PLAY; camera=0; wand=false; staff=false; supportCd=0;
        enemies.clear(); shots.clear();
        player=new Actor(130); player.hp=player.maxHp=100;
        companion=new Actor(65); companion.hp=companion.maxHp=140;
        int[] xs={650,1030,1410,1810,2220,2650,3100,3500};
        for(int x:xs) enemies.add(new Enemy(x,false));
        enemies.add(new Enemy(3860,true));
    }

    void update(float dt){
        supportCd=Math.max(0,supportCd-dt);
        float radius=Math.max(85,getWidth()*.09f);
        float dx=joyPointer>=0?clamp((joyX-joyStartX)/radius,-1,1):0;
        float dy=joyPointer>=0?clamp((joyY-joyStartY)/radius,-1,1):0;
        player.vx=dx*360;
        if(Math.abs(dx)>.05f) player.dir=dx>0?1:-1;
        if(dy<-.48f && player.grounded && !jumpLatch){player.vy=-700; jumpLatch=true;}
        if(dy>-.2f) jumpLatch=false;
        step(player,dt);

        companion.cd=Math.max(0,companion.cd-dt);
        Enemy near=nearest(companion.x);
        if(near!=null && companion.hp>0){
            float d=near.x-companion.x;
            companion.dir=d>=0?1:-1;
            if(Math.abs(d)>70) companion.vx=companion.dir*290;
            else {
                companion.vx*=.6f;
                if(companion.cd<=0){near.hp-=24; near.vx+=companion.dir*130; companion.cd=.55f;}
            }
        } else companion.vx*=.8f;
        step(companion,dt);

        for(Enemy e:enemies){
            if(e.hp<=0) continue;
            e.cd=Math.max(0,e.cd-dt);
            Actor target=(companion.hp>0 && Math.abs(e.x-companion.x)<Math.abs(e.x-player.x))?companion:player;
            float d=target.x-e.x; e.dir=d>=0?1:-1;
            if(Math.abs(d)<520 && Math.abs(d)>55) e.vx+=e.dir*(e.boss?520:390)*dt;
            e.vx*=.90f; step(e,dt);
            if(Math.abs(d)<58 && e.cd<=0){
                target.hp-=e.boss?22:11; target.vx+=e.dir*150; e.cd=e.boss?.72f:.95f;
            }
        }

        Iterator<Shot> it=shots.iterator();
        while(it.hasNext()){
            Shot s=it.next(); s.life-=dt; s.x+=s.vx*dt; s.y+=s.vy*dt;
            if(s.gravity) s.vy+=240*dt;
            boolean h=false;
            for(Enemy e:enemies) if(e.hp>0 && s.x>e.x && s.x<e.x+42 && s.y>e.y && s.y<e.y+80){
                e.hp-=s.damage; e.vx+=Math.signum(s.vx)*120; h=true; break;
            }
            if(h||s.life<=0) it.remove();
        }

        if(!wand && player.x>1540 && player.x<1660) wand=true;
        if(!staff && player.x>2760 && player.x<2880) staff=true;

        camera+=(clamp(player.x-getWidth()*.30f,0,Math.max(0,WORLD-getWidth()))-camera)*Math.min(1,dt*6);
        if(player.hp<=0) mode=Mode.DEAD;
        boolean alive=false; for(Enemy e:enemies) if(e.hp>0){alive=true;break;}
        if(!alive) mode=Mode.WIN;
    }

    void step(Actor a,float dt){
        a.cd=Math.max(0,a.cd-dt);
        a.vy+=GRAVITY*dt; a.x+=a.vx*dt; a.y+=a.vy*dt;
        a.x=clamp(a.x,0,WORLD-42);
        float gy=ground()-a.h;
        if(a.y>=gy){a.y=gy;a.vy=0;a.grounded=true;} else a.grounded=false;
    }

    Enemy nearest(float x){
        Enemy best=null; float bd=Float.MAX_VALUE;
        for(Enemy e:enemies) if(e.hp>0){float d=Math.abs(e.x-x); if(d<bd){bd=d;best=e;}}
        return best;
    }

    void attack(){
        if(mode!=Mode.PLAY || player.cd>0) return;
        switch(loadout){
            case SWORD: melee(80,28); player.cd=.32f; break;
            case AXE: melee(96,44); player.cd=.62f; break;
            case BOW: shoot(670,-50,26,true,Color.rgb(224,197,124)); player.cd=.50f; break;
            case CROSSBOW: shoot(860,0,42,false,Color.LTGRAY); player.cd=.86f; break;
            case SUPPORT:
                if(wand||staff){
                    shoot(staff?760:650,0,staff?42:32,false,Color.rgb(172,145,255));
                    player.cd=staff?.43f:.58f;
                } else if(supportCd<=0){
                    player.hp=Math.min(player.maxHp,player.hp+16);
                    companion.hp=Math.min(companion.maxHp,companion.hp+30);
                    supportCd=1.7f; player.cd=.35f;
                }
                break;
        }
    }

    void melee(float range,int damage){
        for(Enemy e:enemies){
            if(e.hp<=0) continue;
            float d=e.x-player.x;
            if(player.dir*d>=0 && Math.abs(d)<range && Math.abs(e.y-player.y)<70){
                e.hp-=damage; e.vx+=player.dir*160;
            }
        }
    }

    void shoot(float speed,float vy,int damage,boolean gravity,int color){
        Shot s=new Shot(); s.x=player.x+player.dir*30+20; s.y=player.y+30;
        s.vx=player.dir*speed; s.vy=vy; s.damage=damage; s.gravity=gravity; s.color=color; s.life=2.5f;
        shots.add(s);
    }

    void drawScene(Canvas c){
        c.drawColor(Color.rgb(9,11,16));
        float g=ground();
        p.setColor(Color.rgb(24,27,38)); c.drawRect(0,g-240,getWidth(),g,p);
        p.setColor(Color.rgb(47,47,55)); c.drawRect(0,g,getWidth(),getHeight(),p);
        p.setColor(Color.rgb(90,90,98)); c.drawRect(0,g,getWidth(),g+5,p);
        p.setColor(Color.rgb(95,77,62)); p.setTextSize(26);
        for(float x=430;x<WORLD;x+=480){float sx=x-camera;if(sx>-30&&sx<getWidth()+30)c.drawText("†",sx,g-18,p);}
    }

    void drawSelect(Canvas c){
        p.setTextAlign(Paint.Align.CENTER); p.setColor(Color.WHITE);
        p.setTextSize(Math.max(27,getWidth()*.032f));
        c.drawText("CHOOSE A WEAPON BEFORE SPAWNING",getWidth()/2f,getHeight()*.23f,p);
        p.setTextSize(Math.max(14,getWidth()*.016f)); p.setColor(Color.LTGRAY);
        c.drawText("A defender skeleton spawns with you and attacks the nearest enemy.",getWidth()/2f,getHeight()*.31f,p);

        float gap=Math.max(8,getWidth()*.01f);
        float bw=Math.min(190,(getWidth()-gap*6)/5f);
        float total=bw*5+gap*4, left=(getWidth()-total)/2f, top=getHeight()*.47f, bh=Math.max(75,getHeight()*.18f);
        for(int i=0;i<5;i++){
            float x=left+i*(bw+gap); choices[i].set(x,top,x+bw,top+bh);
            p.setColor(Color.rgb(31,35,46)); c.drawRoundRect(choices[i],15,15,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(Color.LTGRAY);c.drawRoundRect(choices[i],15,15,p);
            p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);p.setTextSize(Math.max(13,bw*.10f));
            c.drawText(names[i],x+bw/2,top+bh*.57f,p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    void drawGame(Canvas c){
        if(player==null)return;
        for(Enemy e:enemies) if(e.hp>0){
            skeleton(c,e.x-camera+21,e.y+62,e.dir,e.boss?1.18f:1f,Color.rgb(215,205,188));
            bar(c,e.x-camera,e.y-9,42,e.hp,e.maxHp);
        }

        if(companion.hp>0){
            skeleton(c,companion.x-camera+21,companion.y+62,companion.dir,1,Color.rgb(175,213,235));
            bar(c,companion.x-camera,companion.y-9,42,companion.hp,companion.maxHp);
        }
        skeleton(c,player.x-camera+21,player.y+62,player.dir,1,Color.rgb(238,232,218));
        bar(c,player.x-camera,player.y-9,42,player.hp,player.maxHp);

        for(Shot s:shots){
            p.setColor(s.color);float x=s.x-camera;
            if(s.color==Color.rgb(172,145,255))c.drawCircle(x,s.y,8,p); else c.drawRect(x-9,s.y-2,x+9,s.y+2,p);
        }

        if(!wand){p.setColor(Color.rgb(181,157,255));c.drawCircle(1600-camera,ground()-48,10,p);}
        if(!staff){p.setColor(Color.rgb(181,157,255));c.drawRect(2810-camera,ground()-90,2817-camera,ground()-35,p);}

        p.setColor(Color.argb(175,0,0,0));c.drawRoundRect(new RectF(14,12,385,93),14,14,p);
        p.setColor(Color.WHITE);p.setTextSize(18);
        c.drawText("Player: "+loadout.name(),28,39,p);
        c.drawText("HP "+Math.max(0,player.hp)+"   Defender "+Math.max(0,companion.hp),28,66,p);
        p.setTextSize(14);p.setColor(Color.LTGRAY);
        c.drawText((wand||staff)?"Attack magic: "+(staff?"STAFF":"WAND"):"Attack magic: LOCKED",28,87,p);

        float r=Math.max(58,Math.min(84,getHeight()*.13f));
        attackBtn.set(getWidth()-r*2.25f,getHeight()-r*2.1f,getWidth()-r*.25f,getHeight()-r*.10f);
        p.setColor(Color.argb(215,52,57,71));c.drawOval(attackBtn,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);p.setColor(Color.WHITE);c.drawOval(attackBtn,p);
        p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(20);p.setColor(Color.WHITE);
        String label=(loadout==Loadout.SUPPORT && !(wand||staff))?"SUPPORT":"ATTACK";
        c.drawText(label,attackBtn.centerX(),attackBtn.centerY()+7,p);p.setTextAlign(Paint.Align.LEFT);

        if(mode==Mode.DEAD||mode==Mode.WIN){
            p.setColor(Color.argb(220,0,0,0));c.drawRect(0,0,getWidth(),getHeight(),p);
            p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setTextSize(40);
            c.drawText(mode==Mode.WIN?"CRYPT CLEARED":"THE BONES HAVE FALLEN",getWidth()/2f,getHeight()*.46f,p);
            p.setTextSize(20);c.drawText("Tap to return to weapon selection",getWidth()/2f,getHeight()*.56f,p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    void skeleton(Canvas c,float x,float feet,int dir,float scale,int color){
        c.save();c.translate(x,feet);c.scale(dir*scale,scale);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);p.setColor(color);
        c.drawCircle(0,-48,13,p);c.drawLine(0,-35,0,-5,p);
        c.drawLine(-9,-28,-20,-11,p);c.drawLine(-20,-11,-14,2,p);
        c.drawLine(9,-28,20,-11,p);c.drawLine(20,-11,25,2,p);
        c.drawLine(-7,-5,-10,18,p);c.drawLine(-10,18,-16,36,p);
        c.drawLine(7,-5,10,18,p);c.drawLine(10,18,16,36,p);
        for(int y=-28;y<-7;y+=7){c.drawLine(0,y,-10,y+5,p);c.drawLine(0,y,10,y+5,p);}
        p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(20,20,20));c.drawCircle(-5,-51,3,p);c.drawCircle(5,-51,3,p);
        c.restore();
    }

    void bar(Canvas c,float x,float y,float w,int hp,int max){
        p.setColor(Color.argb(180,0,0,0));c.drawRect(x,y,x+w,y+6,p);
        p.setColor(Color.WHITE);c.drawRect(x,y,x+w*clamp(hp/(float)max,0,1),y+6,p);
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        int a=e.getActionMasked(), idx=e.getActionIndex(), id=e.getPointerId(idx);
        float x=e.getX(idx), y=e.getY(idx);

        if(mode==Mode.SELECT){
            if(a==MotionEvent.ACTION_UP){
                for(int i=0;i<choices.length;i++) if(choices[i].contains(x,y)){start(Loadout.values()[i]);return true;}
            }
            return true;
        }
        if(mode==Mode.DEAD||mode==Mode.WIN){
            if(a==MotionEvent.ACTION_UP){mode=Mode.SELECT;player=null;companion=null;enemies.clear();shots.clear();}
            return true;
        }

        if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN){
            if(attackBtn.contains(x,y)&&attackPointer<0){attackPointer=id;attack();return true;}
            if(x<getWidth()*.56f&&joyPointer<0){
                joyPointer=id;joyStartX=joyX=x;joyStartY=joyY=y;jumpLatch=false;return true;
            }
        }
        if(a==MotionEvent.ACTION_MOVE){
            for(int i=0;i<e.getPointerCount();i++) if(e.getPointerId(i)==joyPointer){joyX=e.getX(i);joyY=e.getY(i);}
            return true;
        }
        if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_CANCEL){
            if(id==joyPointer||a==MotionEvent.ACTION_CANCEL){joyPointer=-1;jumpLatch=false;}
            if(id==attackPointer||a==MotionEvent.ACTION_CANCEL)attackPointer=-1;
            return true;
        }
        return true;
    }

    static class Actor{
        float x,y,vx,vy,w=42,h=78,cd;int hp=100,maxHp=100,dir=1;boolean grounded;
        Actor(float x){this.x=x;this.y=0;}
    }
    static class Enemy extends Actor{
        boolean boss;Enemy(float x,boolean b){super(x);boss=b;hp=maxHp=b?320:70;h=b?92:78;}
    }
    static class Shot{
        float x,y,vx,vy,life;int damage,color;boolean gravity;
    }
}
